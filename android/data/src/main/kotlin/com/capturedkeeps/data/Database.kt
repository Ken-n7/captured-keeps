package com.capturedkeeps.data

import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

@Dao
interface CkDao {
    @Upsert suspend fun saveStaff(row: StaffRow)
    @Query("SELECT * FROM staff ORDER BY name") suspend fun staff(): List<StaffRow>
    @Query("SELECT * FROM staff WHERE id = :id") suspend fun staffRow(id: String): StaffRow?

    @Upsert suspend fun saveEventRow(row: EventRow)
    @Query("DELETE FROM packages WHERE eventId = :eventId") suspend fun deletePackages(eventId: String)
    @Insert suspend fun insertPackages(rows: List<PackageRow>)
    @Query("SELECT * FROM events WHERE id = :id") suspend fun eventRow(id: String): EventRow?
    @Query("SELECT * FROM events ORDER BY startDate IS NULL, startDate, name") suspend fun eventRows(): List<EventRow>
    @Query("SELECT * FROM packages WHERE eventId = :eventId") suspend fun packages(eventId: String): List<PackageRow>

    /** Packages are replaced wholesale: sessions keep their own snapshot, so nothing points at old rows. */
    @Transaction
    suspend fun saveEvent(row: EventRow, packages: List<PackageRow>) {
        saveEventRow(row)
        deletePackages(row.id)
        insertPackages(packages)
    }

    @Insert suspend fun insertSession(row: SessionRow)
    @Update suspend fun updateSession(row: SessionRow)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionRow?
    @Query("SELECT * FROM sessions WHERE saleId = :saleId") suspend fun sessionsForSale(saleId: String): List<SessionRow>
    @Query("SELECT * FROM sessions WHERE eventId = :eventId ORDER BY startedAt") suspend fun sessions(eventId: String): List<SessionRow>
    @Query("SELECT * FROM sessions WHERE state IN ('STARTED','CAPTURING','REVIEWING','PRINTING')")
    suspend fun unfinishedSessions(): List<SessionRow>

    @Upsert suspend fun savePrintJob(row: PrintJobRow)
    @Query("SELECT * FROM print_jobs WHERE id = :id") suspend fun printJob(id: String): PrintJobRow?
    @Query("SELECT * FROM print_jobs WHERE sessionId = :sessionId ORDER BY createdAt") suspend fun printJobs(sessionId: String): List<PrintJobRow>
    @Query("SELECT * FROM print_jobs WHERE state IN ('QUEUED','SENDING','FAILED','UNKNOWN') ORDER BY createdAt")
    suspend fun openPrintJobs(): List<PrintJobRow>

    @Insert suspend fun appendLedger(row: LedgerRow)
    @Query("SELECT * FROM ledger ORDER BY at, rowid") suspend fun ledger(): List<LedgerRow>

    @Upsert suspend fun saveLayout(row: LayoutRow)
    @Query("SELECT * FROM layouts") suspend fun layouts(): List<LayoutRow>
    @Query("DELETE FROM layouts WHERE id = :id") suspend fun deleteLayout(id: String)

    @Insert suspend fun appendAudit(rows: List<AuditRow>)
    @Query("SELECT * FROM audit WHERE entityId = :entityId ORDER BY at, rowid") suspend fun audit(entityId: String): List<AuditRow>
}

@Database(
    entities = [StaffRow::class, EventRow::class, PackageRow::class, SessionRow::class, PrintJobRow::class,
        LedgerRow::class, AuditRow::class, LayoutRow::class],
    version = 3,
    // Every schema change from here on gets a migration so tablets keep their records (schemas/ holds each version).
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = V1toV2::class), // staff.active
        AutoMigration(from = 2, to = 3), // layouts table
    ],
)
abstract class CkDatabase : RoomDatabase() {
    abstract fun dao(): CkDao
}

/**
 * Rules SQLite enforces itself, so no code path (or future bug) can get around them.
 * Triggers, not indexes: Room validates indexes after a migration but ignores triggers.
 */
internal val APPEND_ONLY = listOf("ledger", "audit").flatMap { t ->
    listOf(
        "CREATE TRIGGER IF NOT EXISTS ${t}_no_update BEFORE UPDATE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
        "CREATE TRIGGER IF NOT EXISTS ${t}_no_delete BEFORE DELETE ON $t BEGIN SELECT RAISE(ABORT, '$t is append-only'); END",
    )
}

/** One payment unlocks one session; a cancelled/failed session frees it again. */
internal val ONE_SESSION_PER_SALE = listOf("INSERT" to "INSERT", "UPDATE" to "UPDATE OF saleId, state").map { (name, on) ->
    """CREATE TRIGGER IF NOT EXISTS sessions_one_per_sale_${name.lowercase()} BEFORE $on ON sessions
       WHEN NEW.saleId IS NOT NULL AND NEW.state NOT IN ('CANCELLED','FAILED')
        AND EXISTS (SELECT 1 FROM sessions WHERE saleId = NEW.saleId AND id != NEW.id AND state NOT IN ('CANCELLED','FAILED'))
       BEGIN SELECT RAISE(ABORT, 'This payment was already used for a session'); END"""
}

private object Guards : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) = (APPEND_ONLY + ONE_SESSION_PER_SALE).forEach(connection::execSQL)
}

/** v2: staff.active; v1's partial unique index (which Room's validation rejects) becomes triggers. */
class V1toV2 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("DROP INDEX IF EXISTS sessions_one_per_sale")
        ONE_SESSION_PER_SALE.forEach(connection::execSQL)
    }
}

/**
 * Laptop: `Room.databaseBuilder<CkDatabase>(name = path)` or `Room.inMemoryDatabaseBuilder<CkDatabase>()`.
 * Android: `Room.databaseBuilder<CkDatabase>(context, context.getDatabasePath("captured-keeps.db").path)`.
 */
fun RoomDatabase.Builder<CkDatabase>.open(): CkDatabase = this
    .setDriver(BundledSQLiteDriver()) // same SQLite on laptop and tablet
    .setQueryCoroutineContext(Dispatchers.IO)
    .addCallback(Guards)
    .build()
