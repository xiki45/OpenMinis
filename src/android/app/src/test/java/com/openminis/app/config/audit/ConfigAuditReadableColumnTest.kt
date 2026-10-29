package com.openminis.app.config.audit

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-config-readable] The audit table's `readable` column on real SQLite
 * (sqlite-jdbc): rows from before the column keep working with NULL, adding
 * the column twice is a no-op, and an older build's explicit SELECT / INSERT
 * still run on the new table (downgrade). The schema follows the project's
 * rules for this standalone database: nullable column with no DEFAULT, no
 * version bump (the old build's default onDowngrade throws), and a no-op
 * onDowngrade from now on.
 */
class ConfigAuditReadableColumnTest {

    /** The table the build before this change created, verbatim. */
    private val legacyCreate = """
        CREATE TABLE config_audit (
            id            TEXT PRIMARY KEY,
            at            INTEGER NOT NULL,
            actor         TEXT NOT NULL,
            session_id    TEXT,
            scope         TEXT NOT NULL,
            key           TEXT NOT NULL,
            old_value     TEXT NOT NULL,
            new_value     TEXT NOT NULL,
            confirmed_at  INTEGER,
            status        TEXT NOT NULL,
            revert_of     TEXT,
            caption       TEXT
        )
    """.trimIndent()

    /** The older build's projection and insert (ContentValues → explicit columns). */
    private val legacyColumns = "id, at, actor, session_id, scope, key, old_value, new_value, confirmed_at, status, revert_of, caption"
    private val legacyInsert = "INSERT OR REPLACE INTO config_audit ($legacyColumns) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun columns(c: Connection): Set<String> = c.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(config_audit)").use { rs ->
            buildSet { while (rs.next()) add(rs.getString("name")) }
        }
    }

    private fun migrate(c: Connection) {
        ConfigAuditLog.missingColumnMigrations(columns(c)).forEach { sql -> c.createStatement().use { it.execute(sql) } }
    }

    private fun insertLegacy(c: Connection, id: String) = c.prepareStatement(legacyInsert).use { st ->
        val vals = listOf(id, 1000L, "agent", null, "groups", "groups.G.entries", "[\"a\"]", "[\"b\"]", null, "applied", null, "cap")
        vals.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
    }

    @Test
    fun `an old table gains the column, keeps its rows, and reads them as NULL`() {
        db().use { c ->
            c.createStatement().use { it.execute(legacyCreate) }
            insertLegacy(c, "old-1")
            assertFalse("readable" in columns(c))

            migrate(c)
            assertTrue("readable" in columns(c))
            c.createStatement().use { st ->
                st.executeQuery("SELECT ${ConfigAuditLog.COLUMNS} FROM config_audit WHERE id = 'old-1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("cap", rs.getString("caption"))
                    assertNull(rs.getString("readable"))
                }
            }
        }
    }

    @Test
    fun `migrating twice is a no-op`() {
        db().use { c ->
            c.createStatement().use { it.execute(legacyCreate) }
            migrate(c)
            assertEquals(emptyList<String>(), ConfigAuditLog.missingColumnMigrations(columns(c)))
            migrate(c)
            assertEquals(1, columns(c).count { it == "readable" })
        }
    }

    @Test
    fun `a fresh table has the column and needs no migration`() {
        db().use { c ->
            c.createStatement().use { it.execute(ConfigAuditLog.CREATE_TABLE) }
            assertTrue("readable" in columns(c))
            assertEquals(emptyList<String>(), ConfigAuditLog.missingColumnMigrations(columns(c)))
        }
    }

    @Test
    fun `downgrade - an older build's SELECT and INSERT still work on the new table`() {
        db().use { c ->
            c.createStatement().use { it.execute(ConfigAuditLog.CREATE_TABLE) }
            // A new row with a description, then an old build writes one.
            c.prepareStatement(
                "INSERT INTO config_audit (${ConfigAuditLog.COLUMNS}) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { st ->
                val vals = listOf("new-1", 2000L, "agent", null, "appearance", "appearance.theme", "\"system\"", "\"dark\"", null, "applied", null, null, "{\"v\":1}")
                vals.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.executeUpdate()
            }
            insertLegacy(c, "old-2")
            c.createStatement().use { st ->
                st.executeQuery("SELECT $legacyColumns FROM config_audit ORDER BY at DESC").use { rs ->
                    val ids = buildList { while (rs.next()) add(rs.getString(1)) }
                    assertEquals(listOf("new-1", "old-2"), ids)
                }
                // ...and upgrading back finds the newer build's description intact.
                st.executeQuery("SELECT readable FROM config_audit WHERE id = 'new-1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("{\"v\":1}", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `the column is nullable with no default and the version is not bumped`() {
        val src = ProductionSources.read("config/audit/ConfigAuditLog.kt")
        assertTrue(Regex("""\${'$'}COL_READABLE\s+TEXT\s*\n""").containsMatchIn(src))
        assertFalse(src.contains("COL_READABLE TEXT NOT NULL") || src.contains("readable TEXT DEFAULT"))
        assertTrue("DB_VERSION stays 1", src.contains("private const val DB_VERSION = 1\n"))
        assertTrue("downgrade never throws", src.contains("override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit"))
    }
}
