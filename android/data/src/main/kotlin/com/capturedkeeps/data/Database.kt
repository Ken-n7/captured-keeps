package com.capturedkeeps.data

import androidx.room.*
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
    @Query("SELECT * FROM print_jobs WHERE sessionId = :sessionId ORDER BY createdAt") suspend fun printJobs(sessionId: String): List<PrintJobRow>
    @Query("SELECT * FROM print_jobs WHERE state IN ('QUEUED','SENDING','FAILED','UNKNOWN') ORDER BY createdAt")
    suspend fun openPrintJobs(): List<PrintJobRow>

    @Insert suspend fun appendLedger(row: LedgerRow)
    @Query("SELECT * FROM ledger ORDER BY at, rowid") suspend fun ledger(): List<LedgerRow>

    @Insert suspend fun appendAudit(rows: List<AuditRow>)
    @Query("SELECT * FROM audit WHERE entityId = :entityId ORDER BY at, rowid") suspend fun audit(entityId: String): List<AuditRow>
}

@Database(
    entities = [StaffRow::class, EventRow::class, PackageRow::class, SessionRow::class, PrintJobRow::class,
        LedgerRow::class, AuditRow::class],
    version = 1,
)
abstract class CkDatabase : RoomDatabase() {
    abstract fun dao(): CkDao
}

/** Rules SQLite enforces itself, so no code path (or future bug) can get around them. */
private object Guards : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        for (table in listOf("ledger", "audit")) {
            connection.execSQL("CREATE TRIGGER ${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END")
            connection.execSQL("CREATE TRIGGER ${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END")
        }
        // One payment unlocks one session; a cancelled/failed session frees it again.
        connection.execSQL("CREATE UNIQUE INDEX sessions_one_per_sale ON sessions(saleId) WHERE saleId IS NOT NULL AND state NOT IN ('CANCELLED','FAILED')")
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
