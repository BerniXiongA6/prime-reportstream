package gov.cdc.prime.router.azure

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gov.cdc.prime.router.OrganizationService
import gov.cdc.prime.router.Report
import gov.cdc.prime.router.ReportId
import gov.cdc.prime.router.Schema
import gov.cdc.prime.router.azure.db.Tables.TASK
import gov.cdc.prime.router.azure.db.enums.TaskAction
import gov.cdc.prime.router.azure.db.tables.ReportFile.REPORT_FILE
import gov.cdc.prime.router.azure.db.tables.pojos.ItemLineage
import gov.cdc.prime.router.azure.db.tables.pojos.ReportFile
import gov.cdc.prime.router.azure.db.tables.pojos.Task
import gov.cdc.prime.router.azure.db.tables.records.TaskRecord
import org.flywaydb.core.Flyway
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSON
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.Driver
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

const val databaseVariable = "POSTGRES_URL"
const val userVariable = "POSTGRES_USER"
const val passwordVariable = "POSTGRES_PASSWORD"

typealias DataAccessTransaction = Configuration

/**
 * A data access layer for the database. Hides JOOQ, Hikari, JDBC and other low-level abstractions.
 */
class DatabaseAccess(private val create: DSLContext) {
    constructor(dataSource: DataSource) : this(DSL.using(dataSource, SQLDialect.POSTGRES))
    constructor(connection: Connection) : this(DSL.using(connection, SQLDialect.POSTGRES))

    class Header(
        val task: Task,
        val reportFile: ReportFile,
        val itemLineages: List<ItemLineage>?, // ok to not have item-level lineage
        val engine: WorkflowEngine = WorkflowEngine(),
        var orgSvc: OrganizationService? = null,
    ) {
        // Populate the header with useful metadata objs, and the blob body.
        val schema: Schema?
        val content: ByteArray?

        init {
            val metadata = engine.metadata
            if (orgSvc == null && reportFile.receivingOrg != null && reportFile.receivingOrgSvc != null) {
                orgSvc = metadata.findService(reportFile.receivingOrg, reportFile.receivingOrgSvc)
                // note:  orgSvc might still be null if not found.
            }

            schema = if (reportFile.schemaName != null)
                metadata.findSchema(reportFile.schemaName)
            else null

            content = if (reportFile.bodyUrl != null)
                engine.blob.downloadBlob(reportFile.bodyUrl)
            else null
        }
    }

    fun checkConnection() {
        create.selectFrom(REPORT_FILE).where(REPORT_FILE.REPORT_ID.eq(UUID.randomUUID())).fetch()
    }

    /**
     * Make the other calls in the context of a SQL transaction
     */
    fun transact(block: (txn: DataAccessTransaction) -> Unit) {
        create.transaction { txn: Configuration -> block(txn) }
    }

    /**
     * Take a report and put into the database after already serializing the body of the report
     */
    fun insertHeader(
        report: Report,
        bodyFormat: String,
        bodyUrl: String,
        nextAction: Event,
        txn: DataAccessTransaction? = null,
    ) {
        fun insert(txn: Configuration) {
            val task = createTaskRecord(report, bodyFormat, bodyUrl, nextAction)
            DSL.using(txn).executeInsert(task)
        }

        if (txn != null) {
            insert(txn)
        } else {
            create.transaction { innerTxn -> insert(innerTxn) }
        }
    }

    /**
     * Fetch a particular task and taskSource. In addition, lock the row for updating, so
     * other connections will not grab it.
     */
    fun fetchAndLockHeader(reportId: ReportId, txn: DataAccessTransaction): Header {
        val ctx = DSL.using(txn)
        val task = ctx
            .selectFrom(TASK)
            .where(TASK.REPORT_ID.eq(reportId))
            .forUpdate()
            .fetchOne()
            ?.into(Task::class.java)
            ?: error("Could not find $reportId that matches a task")

        val reportFile = ActionHistory.fetchReportFile(reportId, ctx)
        ActionHistory.sanityCheckReport(task, reportFile, false)
        val itemLineages = ActionHistory.fetchItemLineagesForReport(reportId, reportFile.itemCount, ctx)

        return Header(task, reportFile, itemLineages)
    }

    /**
     * Fetch all tasks associated with a receiver. In addition, lock the rows for updating, so
     * other connections will not grab it.
     */
    fun fetchAndLockHeaders(
        nextAction: TaskAction,
        at: OffsetDateTime?,
        receiver: OrganizationService,
        limit: Int,
        txn: DataAccessTransaction,
    ): List<Header> {
        val cond = if (at == null) {
            TASK.RECEIVER_NAME.eq(receiver.fullName)
                .and(TASK.NEXT_ACTION.eq(nextAction))
        } else {
            TASK.RECEIVER_NAME.eq(receiver.fullName)
                .and(TASK.NEXT_ACTION.eq(nextAction))
                .and(TASK.NEXT_ACTION_AT.eq(at))
        }
        val ctx = DSL.using(txn)
        val tasks = ctx
            .selectFrom(TASK)
            .where(cond)
            .limit(limit)
            .forUpdate()
            .skipLocked() // Allows the same query to run in parallel. Otherwise, the query would lock the table.
            .fetch()
            .into(Task::class.java)

        val ids = tasks.map { it.reportId }
        val reportFiles = ids
            .map { ActionHistory.fetchReportFile(it, ctx) }
            .map { (it.reportId as ReportId) to it }
            .toMap()
        ActionHistory.sanityCheckReports(tasks, reportFiles, false)

        // taskSources seems erroneous.  All the sources for all Tasks are attached to each indiv. task. ?
        // todo remove the !!
        return tasks.map { Header(it, reportFiles[it.reportId]!!, null) }
    }

    fun fetchDownloadableReportFiles(
        since: OffsetDateTime?,
        receiverName: String,
    ): List<ReportFile> {
        return ActionHistory.fetchDownloadableReportFiles(since, receiverName, create)
    }

    fun fetchHeader(
        reportId: ReportId,
        orgName: String
    ): Header {

        val task = create
            .selectFrom(TASK)
            .where(TASK.REPORT_ID.eq(reportId).and(TASK.RECEIVER_NAME.like("$orgName%")).and(TASK.SENT_AT.isNotNull()))
            .fetchOne()
            ?.into(Task::class.java)
            ?: error("Could not find $reportId/$orgName that matches a task")

        val reportFile = ActionHistory.fetchReportFile(reportId, create)
        ActionHistory.sanityCheckReport(task, reportFile, false)
        val itemLineages = ActionHistory.fetchItemLineagesForReport(reportId, reportFile.itemCount, create)
        return Header(task, reportFile, itemLineages)
    }

    /**
     * Update the header of a report with new values
     */
    fun updateHeader(
        reportId: ReportId,
        currentEventAction: Event.EventAction,
        nextEventAction: Event.EventAction,
        nextActionAt: OffsetDateTime? = null,
        retryToken: String? = null,
        txn: DataAccessTransaction,
    ) {
        fun finishedField(currentEventAction: Event.EventAction): Field<OffsetDateTime> {
            return when (currentEventAction) {
                Event.EventAction.RECEIVE -> TASK.TRANSLATED_AT
                Event.EventAction.TRANSLATE -> TASK.TRANSLATED_AT
                Event.EventAction.BATCH -> TASK.BATCHED_AT
                Event.EventAction.SEND -> TASK.SENT_AT
                Event.EventAction.WIPE -> TASK.WIPED_AT

                Event.EventAction.BATCH_ERROR,
                Event.EventAction.SEND_ERROR,
                Event.EventAction.WIPE_ERROR -> TASK.ERRORED_AT

                Event.EventAction.NONE -> error("Internal Error: NONE currentAction")
            }
        }

        DSL
            .using(txn)
            .update(TASK)
            .set(TASK.NEXT_ACTION, nextEventAction.toTaskAction())
            .set(TASK.NEXT_ACTION_AT, nextActionAt)
            .set(TASK.RETRY_TOKEN, if (retryToken != null) JSON.valueOf(retryToken) else null)
            .set(finishedField(currentEventAction), OffsetDateTime.now())
            .where(TASK.REPORT_ID.eq(reportId))
            .execute()
    }

    companion object {
        /**
         * Create a connection pool
         *
         * Dev Note: Why a connection pool with a "stateless" Azure function? The
         * reason is that Azure functions are actually deployed in a web server.
         * That is functions amortize startup costs by reusing an existing process for a function invocation.
         * Hence, a connection pool is a win in latency after the first initialization.
         */
        val hikariDataSource: HikariDataSource by lazy {
            DriverManager.registerDriver(Driver())

            val password = System.getenv(passwordVariable)
            val user = System.getenv(userVariable)
            val databaseUrl = System.getenv(databaseVariable)
            val config = HikariConfig()
            config.jdbcUrl = databaseUrl
            config.username = user
            config.password = password
            config.addDataSourceProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource")
            config.addDataSourceProperty("cachePrepStmts", "true")
            config.addDataSourceProperty("prepStmtCacheSize", "250")
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            // See this info why these are a good value
            //  https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
            config.minimumIdle = 2
            config.maximumPoolSize = 8
            // This strongly recommended to be set "be several seconds shorter than any database or infrastructure
            // imposed connection time limit". Not sure what value is but have observed that connection are closed
            // after about 10 minutes
            config.maxLifetime = 180000
            val dataSource = HikariDataSource(config)

            val flyway = Flyway.configure().dataSource(dataSource).load()
            flyway.migrate()

            dataSource
        }

        val dataSource: DataSource get() = hikariDataSource

        fun createTaskRecord(
            report: Report,
            bodyFormat: String,
            bodyUrl: String,
            nextAction: Event,
        ): TaskRecord {
            return TaskRecord(
                report.id,
                nextAction.eventAction.toTaskAction(),
                nextAction.at,
                report.schema.name,
                report.destination?.fullName ?: "",
                report.itemCount,
                bodyFormat,
                bodyUrl,
                report.createdDateTime,
                null,
                null,
                null,
                null,
                null,
                null
            )
        }

        fun createTask(
            report: Report,
            bodyFormat: String,
            bodyUrl: String,
            nextAction: Event,
        ): Task {
            return Task(
                report.id,
                nextAction.eventAction.toTaskAction(),
                nextAction.at,
                report.schema.name,
                report.destination?.fullName ?: "",
                report.itemCount,
                bodyFormat,
                bodyUrl,
                report.createdDateTime,
                null,
                null,
                null,
                null,
                null,
                null,
            )
        }
    }
}