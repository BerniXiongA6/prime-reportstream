package gov.cdc.prime.router.transport

import com.microsoft.azure.functions.ExecutionContext
import gov.cdc.prime.router.OrganizationService
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.SFTPTransportType
import gov.cdc.prime.router.TransportType
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemorySourceFile
import net.schmizz.sshj.xfer.LocalSourceFile
import java.io.IOException
import java.io.InputStream
import java.util.logging.Level

class SftpTransport : ITransport {
    private val hl7MessageDelimiter = "\r\r"

    override fun send(
        orgService: OrganizationService,
        transportType: TransportType,
        contents: ByteArray,
        reportId: ReportId,
        retryItems: RetryItems?,
        context: ExecutionContext
    ): RetryItems? {
        val sftpTransportType = transportType as SFTPTransportType
        val host: String = sftpTransportType.host
        val port: String = sftpTransportType.port
        return try {
            val (user, pass) = lookupCredentials(orgService.fullName)

            // context.logger.log(Level.INFO, "About to sftp upload ${sftpTransportType.filePath}/$fileName to $user at $host:$port (orgService = ${orgService.fullName})")
            if (orgService.format == OrganizationService.Format.HL7) {
                val extension = orgService.format.toExt()
                val baseName = "${orgService.fullName.replace('.', '-')}-$reportId"
                uploadMultipleFiles(
                    host, port, user, pass, sftpTransportType.filePath, baseName, extension,
                    contents = String(contents), delimiter = hl7MessageDelimiter
                )
            } else {
                val extension = orgService.format.toExt()
                val fileName = "${orgService.fullName.replace('.', '-')}-$reportId.$extension"
                uploadFile(host, port, user, pass, sftpTransportType.filePath, fileName, contents, context)
            }

            // context.logger.log(Level.INFO, "Successful sftp upload of $fileName")
            null
        } catch (ioException: IOException) {
            context.logger.log(
                Level.WARNING,
                "FAILED Sftp upload of reportId $reportId to $host:$port  (orgService = ${orgService.fullName})\"",
                ioException
            )
            RetryToken.allItems
        } // let non-IO exceptions be caught by the caller
    }

    private fun lookupCredentials(orgName: String): Pair<String, String> {

        val envVarLabel = orgName.replace(".", "__").replace('-', '_').toUpperCase()

        val user = System.getenv("${envVarLabel}__USER") ?: ""
        val pass = System.getenv("${envVarLabel}__PASS") ?: ""

        if (user.isBlank() || pass.isBlank())
            error("Unable to find SFTP credentials for $orgName")

        return Pair(user, pass)
    }

    private fun uploadFile(
        host: String,
        port: String,
        user: String,
        pass: String,
        path: String,
        fileName: String,
        contents: ByteArray,
        context: ExecutionContext // TODO: temp fix to add logging
    ) {
        val sshClient = SSHClient()
        try {
            sshClient.addHostKeyVerifier(PromiscuousVerifier())
            sshClient.connect(host, port.toInt())
            sshClient.authPassword(user, pass)
            putOneFile(sshClient, contents, fileName, path)
        } finally {
            sshClient.disconnect()
            // context.logger.log(Level.INFO, "SFTP DISCONNECT succeeded: $fileName")
        }
    }

    private fun uploadMultipleFiles(
        host: String,
        port: String,
        user: String,
        pass: String,
        path: String,
        baseName: String,
        extension: String,
        contents: String,
        delimiter: String,
    ) {
        val sshClient = SSHClient()
        try {
            sshClient.addHostKeyVerifier(PromiscuousVerifier())
            sshClient.connect(host, port.toInt())
            sshClient.authPassword(user, pass)
            val items = contents.split(delimiter)
            items.forEachIndexed { index, item ->
                if (item.isBlank()) return@forEachIndexed
                putOneFile(sshClient, item.toByteArray(), "$baseName-$index.$extension", path)
            }
        } finally {
            sshClient.disconnect()
            // context.logger.log(Level.INFO, "SFTP DISCONNECT succeeded: $fileName")
        }
    }

    private fun putOneFile(sshClient: SSHClient, contents: ByteArray, fileName: String, path: String) {
        val client = sshClient.newSFTPClient()
        client.fileTransfer.preserveAttributes = false
        client.use { client ->
            client.put(makeSourceFile(contents, fileName), path + "/" + fileName)
            // TODO: remove this over logging when bug is fixed
            // context.logger.log(Level.INFO, "SFTP PUT succeeded: $fileName")
        }
    }

    private fun makeSourceFile(contents: ByteArray, fileName: String): LocalSourceFile {
        return object : InMemorySourceFile() {
            override fun getName(): String { return fileName }
            override fun getLength(): Long { return contents.size.toLong() }
            override fun getInputStream(): InputStream { return contents.inputStream() }
            override fun isDirectory(): Boolean { return false }
            override fun isFile(): Boolean { return true }
            override fun getPermissions(): Int { return 777 }
        }
    }
}