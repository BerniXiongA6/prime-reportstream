package gov.cdc.prime.router.tokens

import com.fasterxml.jackson.annotation.JsonProperty
import com.microsoft.azure.functions.HttpRequestMessage
import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk.KeyType
import gov.cdc.prime.router.azure.ActionHistory
import gov.cdc.prime.router.azure.WorkflowEngine
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SigningKeyResolverAdapter
import org.apache.logging.log4j.kotlin.Logging
import java.lang.IllegalArgumentException
import java.lang.RuntimeException
import java.security.Key
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * Implementation of two-legged auth, using a sender's public key pre-authorized
 * by a trusted party at the sender.  Per this guide:
 *    https://hl7.org/fhir/uv/bulkdata/authorization/index.html
 *
 * Note: This is currently not implemented as a child of AuthenticationVerifier
 * since this auth uses a 'scope', not a PrincipalLevel
 *
 *
 */
class TokenAuthentication(val jtiCache: JtiCache) : Logging {
    private val MAX_CLOCK_SKEW_SECONDS: Long = 60
    private val EXPIRATION_SECONDS = 300

    /**
     *  convenience method to log in two places
     */
    private fun logErr(actionHistory: ActionHistory?, msg: String) {
        actionHistory?.trackActionResult(msg)
        logger.error(msg)
    }

    /**
     * @return true if jwsString is a validly signed Sender token, false if it is unauthorized
     * If it is valid, then its ok to move to the next step, then give the sender an Access token.
     */
    fun checkSenderToken(
        jwsString: String,
        senderPublicKeyFinder: SigningKeyResolverAdapter,
        actionHistory: ActionHistory? = null,
    ): Boolean {
        try {
            // Note: this does an expired token check as well.  throws JwtException on problems.
            val jws = Jwts.parserBuilder()
                .setAllowedClockSkewSeconds(MAX_CLOCK_SKEW_SECONDS)
                .setSigningKeyResolver(senderPublicKeyFinder) // all the work is in senderPublicKeyFinder
                .build()
                .parseClaimsJws(jwsString)
            val jti = jws.body.id
            val exp = jws.body.expiration
            if (jti == null) {
                logErr(actionHistory, "SenderToken has null JWT ID.  Rejecting.")
                return false
            }
            val expiresAt = exp.toInstant().atOffset(ZoneOffset.UTC)
            if (expiresAt.isBefore(OffsetDateTime.now())) {
                logErr(actionHistory, "SenderToken $jti has expired, at $expiresAt.  Rejecting.")
                return false
            }
            return jtiCache.isJTIOk(jti, expiresAt) // check for replay attacks
        } catch (ex: JwtException) {
            logErr(actionHistory, "Rejecting SenderToken JWT: $ex")
            return false
        } catch (e: IllegalArgumentException) {
            logErr(actionHistory, "Rejecting SenderToken JWT: $e")
            return false
        }
    }

    fun createAccessToken(
        scopeAuthorized: String,
        lookup: ReportStreamSecretFinder,
        actionHistory: ActionHistory? = null,
    ): AccessToken {
        if (scopeAuthorized.isEmpty() || scopeAuthorized.isBlank()) error("Empty or blank scope request")
        val secret = lookup.getReportStreamTokenSigningSecret()
        // Using Integer seconds to stay consistent with the JWT token spec, which uses seconds.
        // Search for 'NumericDate' in https://tools.ietf.org/html/rfc7519#section-2
        // This will break in 2038, which, actually, isn't that far off...
        val expiresInSeconds = EXPIRATION_SECONDS
        val expiresAtSeconds = ((System.currentTimeMillis() / 1000) + expiresInSeconds).toInt()
        val expirationDate = Date(expiresAtSeconds.toLong() * 1000)
        val subject = scopeAuthorized + "_" + UUID.randomUUID()
        // Keep it small:  The signed token we send back only has two claims in it.
        val token = Jwts.builder()
            .setExpiration(expirationDate) // exp
            .claim("scope", scopeAuthorized)
            .claim("sub", subject)
            .signWith(secret).compact()
        val msg = "AccessToken $subject successfully created for $scopeAuthorized. Expires at $expirationDate"
        actionHistory?.trackActionResult(msg)
        logger.info(msg)
        return AccessToken(subject, token, "bearer", expiresInSeconds, expiresAtSeconds, scopeAuthorized)
    }

    fun checkAccessToken(request: HttpRequestMessage<String?>, desiredScope: String): Claims? {
        val bearerComponent = request.headers["authorization"]
        if (bearerComponent == null) {
            logger.error("Missing Authorization header.  AccessToken Unauthorized.")
            return null
        }
        val accessToken = bearerComponent.split(" ")[1]
        if (accessToken.isEmpty()) {
            logger.error("Request has Authorization header but no token.  AccessToken Unauthorized")
            return null
        }
        return checkAccessToken(accessToken, desiredScope, FindReportStreamSecretInVault())
    }

    /**
     * lookup is a call back to get the ReportStream secret used to sign the token.  Using a callback
     * makes this easy to test - can pass in a static test secret
     * This does not need to be a public/private key.
     */
    fun checkAccessToken(accessToken: String, desiredScope: String, lookup: ReportStreamSecretFinder): Claims? {
        try {
            val secret = lookup.getReportStreamTokenSigningSecret()
            // Check the signature.  Throws JwtException on problems.
            val jws = Jwts.parserBuilder()
                .setSigningKey(secret)
                .build()
                .parseClaimsJws(accessToken)
            if (jws.body == null) {
                logger.error("AccessToken check failed - no claims.  Unauthorized.")
                return null
            }
            val subject = jws.body["sub"] as? String
            if (subject == null) {
                logger.error("AccessToken missing subject.  Unauthorized.")
                return null
            } else {
                logger.info("checking AccessToken $subject")
            }
            if (isExpiredToken(jws.body.expiration)) {
                logger.error("AccessToken $subject expired.  Unauthorized.")
                return null
            }
            val scope = jws.body["scope"] as? String
            if (scope == null) {
                logger.error("Missing scope claim in AccessToken $subject.  Unauthorized.")
                return null
            }
            if (!Scope.scopeListContainsScope(scope, desiredScope)) {
                logger.error(
                    "In AccessToken $subject," +
                        " signed key has scope $scope, but requests $desiredScope. Unauthorized"
                )
                return null
            }
            logger.info("AccessToken $subject : validated.")
            return jws.body
        } catch (ex: JwtException) {
            logger.error("AccessToken Unauthorized: $ex")
            return null
        } catch (exc: RuntimeException) {
            logger.error("AccessToken Unauthorized: $exc")
            return null
        }
    }

    companion object : Logging {
        fun isExpiredToken(exp: Date): Boolean {
            return (Date().after(exp)) // no need to include clock skew, since we generated token ourselves
        }
    }
}

/**
 * Defined per the FHIR standard
 *    https://hl7.org/fhir/uv/bulkdata/authorization/index.html
 */
// todo get this to work:  @JsonNaming(PropertyNamingStrategy.SnakeCaseStrategy.class)
data class AccessToken(
    @JsonProperty("sub")
    val sub: String, // a unique subject / id for this token, suitable for tracing
    @JsonProperty("access_token")
    val accessToken: String, // access_token - required - The access token issued by the authorization server.
    @JsonProperty("token_type")
    val tokenType: String, // token_type	required - Fixed value: bearer.
    @JsonProperty("expires_in")
    val expiresIn: Int, // seconds until expiration, eg, 300
    @JsonProperty("expires_at_seconds")
    val expiresAtSeconds: Int, //  unix time IN SECONDS, of the expiration time.
    @JsonProperty("scope")
    val scope: String, // scope	required - Scope of access authorized. can be different from the scopes requested
)

/**
 * This is used during validation of a SenderToken.
 *
 * Implementation of a callback function used to find the public key for
 * a given Sender, kid, and alg.   Lookup in the Settings table.
 *  todo:  the FHIR spec calls for allowing a set of keys. However, this callback only allows for one.
 */
class FindSenderKeyInSettings(val scope: String) : SigningKeyResolverAdapter(), Logging {
    var errorMsg: String? = null

    fun err(shortMsg: String): Key? {
        errorMsg = "AccessToken Request Denied: Error while requesting $scope: $shortMsg"
        logger.error(errorMsg!!)
        return null
    }

    override fun resolveSigningKey(jwsHeader: JwsHeader<*>?, claims: Claims): Key? {
        errorMsg = null
        if (jwsHeader == null) return err("JWT has missing header")
        val issuer = claims.issuer
        val kid = jwsHeader.keyId
        val alg = jwsHeader.algorithm
        val kty = KeyType.forAlgorithm(Algorithm.parse(alg))
        val sender = WorkflowEngine().settings.findSender(issuer) ?: return err("No such sender fullName $issuer")
        if (sender.keys == null) return err("No auth keys associated with sender $issuer")
        if (!Scope.isValidScope(scope, sender)) return err("Invalid scope for this sender: $scope")
        sender.keys.forEach { jwkSet ->
            if (Scope.scopeListContainsScope(jwkSet.scope, scope)) {

                // find by kid and kty
                val key = jwkSet.keys.find { jwk -> (jwk.kid == kid && jwk.kty == kty.value) }

                return when {
                    key == null -> null
                    kty == KeyType.EC -> key.toECPublicKey()
                    kty == KeyType.RSA -> key.toRSAPublicKey()
                    else -> null
                }
            }
        }
        // Failed to find any key for this sender, with the requested/desired scope.
        return err("Unable to find auth key for $issuer with scope=$scope, kid=$kid, and alg=$alg")
    }
}