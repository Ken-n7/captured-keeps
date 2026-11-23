package com.capturedkeeps.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import androidx.sqlite.SQLiteException

/** Every schema version upgrades without losing records. Add a case per new version. */
class MigrationTest {
    @Test fun `1 to 2 keeps staff and makes them active`() {
        val file = File.createTempFile("ckmig", ".db").also { it.delete(); it.deleteOnExit() }
        val helper = MigrationTestHelper(Path(System.getProperty("schemaDir")), file.toPath(), BundledSQLiteDriver(), CkDatabase::class)
        helper.createDatabase(1).apply {
            // Exactly what v1 installs had: its guards, including the partial index v2 replaces.
            APPEND_ONLY.forEach(::execSQL)
            execSQL("CREATE UNIQUE INDEX sessions_one_per_sale ON sessions(saleId) WHERE saleId IS NOT NULL AND state NOT IN ('CANCELLED','FAILED')")
            execSQL("INSERT INTO staff (id, name, role, pinHash, passwordHash) VALUES ('o', 'Owner', 'OWNER', 'p', 'h')")
            close()
        }
        val db = helper.runMigrationsAndValidate(2)
        db.prepare("SELECT name, passwordHash, active FROM staff").use {
            it.step()
            assertEquals(listOf("Owner", "h", "1"), listOf(it.getText(0), it.getText(1), it.getLong(2).toString()))
        }
        // The one-payment-one-session guard still holds after the upgrade.
        val insert = "INSERT INTO sessions VALUES ('%s','e','d','p','P',1,1,0,'l',1,'sale1','CAPTURING',0,NULL,0,NULL)"
        db.execSQL(insert.format("s1"))
        assertFailsWith<SQLiteException> { db.execSQL(insert.format("s2")) }
        db.close()
    }

    @Test fun `2 to 3 adds layouts and keeps everything else`() {
        val file = File.createTempFile("ckmig", ".db").also { it.delete(); it.deleteOnExit() }
        val helper = MigrationTestHelper(Path(System.getProperty("schemaDir")), file.toPath(), BundledSQLiteDriver(), CkDatabase::class)
        helper.createDatabase(2).apply {
            execSQL("INSERT INTO staff (id, name, role, pinHash, passwordHash, active) VALUES ('o', 'Owner', 'OWNER', 'p', 'h', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(3)
        db.prepare("SELECT (SELECT count(*) FROM staff), (SELECT count(*) FROM layouts)").use {
            it.step(); assertEquals(1L to 0L, it.getLong(0) to it.getLong(1))
        }
        db.close()
    }
}
