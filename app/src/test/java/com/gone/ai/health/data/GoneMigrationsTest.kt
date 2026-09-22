package com.gone.ai.health.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the hand-written 1 -> 2 migration against Room's own exported schema.
 *
 * WHY THIS TEST EARNS ITS KEEP
 *
 * A hand-written migration can drift from the entity definitions, and the symptom is
 * a crash on upgrade for users who already have data — the hardest failure to catch
 * in development, because a fresh install never runs the migration at all.
 *
 * `exportSchema = true` makes Room publish the canonical DDL for every version. This
 * test reads that file and asserts the migration produces exactly the same schema. It
 * is precisely what `fallbackToDestructiveMigration()` made impossible: there was no
 * migration to check, and the failure mode was silent data loss instead of a red test.
 *
 * The schema JSON is parsed with a regex rather than a JSON library on purpose — unit
 * tests here run against the stubbed android.jar, where `org.json` returns default
 * values, and pulling in a parser for one assertion is not worth the dependency.
 */
class GoneMigrationsTest {

    private companion object {
        const val SCHEMA_DIR = "schemas/com.gone.ai.data.library.GoneDatabase"
    }

    /** Unit tests may run from the module dir or the repo root depending on invocation. */
    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("$SCHEMA_DIR/$version.json"),
            File("app/$SCHEMA_DIR/$version.json"),
            File("../app/$SCHEMA_DIR/$version.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "Room schema $version.json not found. Looked in: " +
                    candidates.joinToString { it.absolutePath } +
                    ". Is exportSchema=true and room.schemaLocation configured?"
            )
    }

    /**
     * Normalise SQL whitespace so the readable multi-line migration compares equal to
     * Room's single-line canonical form.
     *
     * Also strips whitespace immediately inside parentheses: `( \`id\` TEXT ... )` and
     * `(\`id\` TEXT ...)` are the same statement to SQLite, and the migration is
     * formatted for a human reviewer rather than for byte-equality with a generator.
     */
    private fun normalise(sql: String): String =
        sql.replace(Regex("\\s+"), " ")
            .replace(Regex("\\(\\s+"), "(")
            .replace(Regex("\\s+\\)"), ")")
            .trim()
            .removeSuffix(";")

    /** Extract every `createSql` value, with ${TABLE_NAME} already substituted. */
    private fun canonicalStatements(version: Int): List<String> {
        val raw = schemaFile(version).readText()

        // Pair each createSql with the tableName that precedes it in the document, so
        // the ${TABLE_NAME} placeholder can be resolved correctly.
        val results = mutableListOf<String>()
        var currentTable = ""
        val token = Regex("\"(tableName|createSql)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        token.findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
            if (key == "tableName") currentTable = value
            else results += value.replace("\${TABLE_NAME}", currentTable)
        }
        return results
    }

    @Test
    fun `both schema versions are exported and committed`() {
        assertTrue("1.json missing", schemaFile(1).isFile)
        assertTrue("2.json missing", schemaFile(2).isFile)
        assertTrue(schemaFile(1).readText().contains("\"version\": 1"))
        assertTrue(schemaFile(2).readText().contains("\"version\": 2"))
    }

    /**
     * Version 1 must contain only the inherited library tables — this is what the
     * migration starts from, and what it must preserve.
     */
    @Test
    fun `version 1 contains only the pre-existing library tables`() {
        val raw = schemaFile(1).readText()
        GoneMigrations.VERSION_1_TABLES.forEach {
            assertTrue("v1 should contain $it", raw.contains("\"$it\""))
        }
        GoneMigrations.MIGRATION_1_2_NEW_TABLES.forEach {
            assertFalse("v1 must not already contain $it", raw.contains("\"tableName\": \"$it\""))
        }
    }

    /** The core assertion: my DDL is byte-equivalent to Room's, modulo whitespace. */
    @Test
    fun `migration DDL matches Room's canonical schema exactly`() {
        val canonical = canonicalStatements(2).map { normalise(it) }
        val mine = GoneMigrations.MIGRATION_1_2_STATEMENTS.map { normalise(it) }

        GoneMigrations.MIGRATION_1_2_NEW_TABLES.forEach { table ->
            val expected = canonical.firstOrNull {
                it.startsWith("CREATE TABLE IF NOT EXISTS `$table`")
            } ?: throw AssertionError("Room did not export a CREATE TABLE for `$table`")

            val actual = mine.firstOrNull {
                it.startsWith("CREATE TABLE IF NOT EXISTS `$table`")
            } ?: throw AssertionError("migration has no CREATE TABLE for `$table`")

            assertEquals("DDL drift for table `$table`", expected, actual)
        }
    }

    /**
     * Indexes matter as much as columns: a missing index turns the cooldown lookup and
     * the per-patient window scan into full table scans as history grows.
     */
    @Test
    fun `migration creates every index Room expects`() {
        val canonicalIndexes = canonicalStatements(2)
            .map { normalise(it) }
            .filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") }
            .filter { ddl -> GoneMigrations.MIGRATION_1_2_NEW_TABLES.any { ddl.contains("`$it`") } }

        val mine = GoneMigrations.MIGRATION_1_2_STATEMENTS.map { normalise(it) }

        assertTrue("Room exported no indexes for the new tables", canonicalIndexes.isNotEmpty())
        canonicalIndexes.forEach { expected ->
            assertTrue("migration is missing index DDL: $expected", mine.contains(expected))
        }
    }

    @Test
    fun `migration covers all four new tables and nothing else`() {
        val created = GoneMigrations.MIGRATION_1_2_STATEMENTS
            .mapNotNull { Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(it)?.groupValues?.get(1) }
            .toSet()
        assertEquals(GoneMigrations.MIGRATION_1_2_NEW_TABLES, created)
    }

    // ── Additivity, asserted rather than asserted-in-a-comment ────────────────

    /**
     * The safety property for existing installs. A migration that dropped or rewrote a
     * version 1 table would destroy a user's saved content on upgrade.
     */
    @Test
    fun `migration is purely additive with no destructive statements`() {
        val destructive = listOf("DROP TABLE", "DROP INDEX", "DELETE FROM", "TRUNCATE", "DROP COLUMN")
        GoneMigrations.MIGRATION_1_2_STATEMENTS.forEach { stmt ->
            val upper = stmt.uppercase()
            destructive.forEach { keyword ->
                assertFalse("destructive statement found: $stmt", upper.contains(keyword))
            }
        }
    }

    @Test
    fun `migration never references a version 1 table`() {
        GoneMigrations.MIGRATION_1_2_STATEMENTS.forEach { stmt ->
            GoneMigrations.VERSION_1_TABLES.forEach { table ->
                assertFalse(
                    "migration touches pre-existing table `$table`: $stmt",
                    stmt.contains(table)
                )
            }
        }
    }

    /** Re-running the migration must not fail, so every statement is idempotent. */
    @Test
    fun `every statement is guarded with IF NOT EXISTS`() {
        GoneMigrations.MIGRATION_1_2_STATEMENTS.forEach { stmt ->
            assertTrue(
                "statement is not idempotent: $stmt",
                stmt.contains("IF NOT EXISTS")
            )
        }
    }

    @Test
    fun `migration is registered for the right version range`() {
        assertEquals(1, GoneMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, GoneMigrations.MIGRATION_1_2.endVersion)
        assertEquals(2, GoneMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, GoneMigrations.MIGRATION_2_3.endVersion)
        assertEquals(3, GoneMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, GoneMigrations.MIGRATION_3_4.endVersion)
        assertEquals(4, GoneMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, GoneMigrations.MIGRATION_4_5.endVersion)
        assertEquals(4, GoneMigrations.ALL.size)
    }

    // ── 2 -> 3 ───────────────────────────────────────────────────────────────

    @Test
    fun `version 3 schema is exported`() {
        assertTrue(schemaFile(3).readText().contains("\"version\": 3"))
    }

    @Test
    fun `2 to 3 creates the new tables exactly as Room defines them`() {
        val canonical = canonicalStatements(3).map { normalise(it) }
        val mine = GoneMigrations.MIGRATION_2_3_STATEMENTS.map { normalise(it) }

        GoneMigrations.MIGRATION_2_3_NEW_TABLES.forEach { table ->
            val expected = canonical.firstOrNull { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") }
                ?: throw AssertionError("Room did not export a CREATE TABLE for `$table`")
            val actual = mine.firstOrNull { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") }
                ?: throw AssertionError("migration has no CREATE TABLE for `$table`")
            assertEquals("DDL drift for table `$table`", expected, actual)
        }

        val expectedIndexes = canonical
            .filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") }
            .filter { ddl -> GoneMigrations.MIGRATION_2_3_NEW_TABLES.any { ddl.contains("`$it`") } }
        assertEquals(4, expectedIndexes.size)
        expectedIndexes.forEach { assertTrue("missing index DDL: $it", mine.contains(it)) }
    }

    /**
     * ALTER TABLE cannot be compared to a CREATE statement directly, so check the result:
     * version 2's columns followed by the added ones must be exactly Room's version 3
     * column list, in order, with the same types.
     */
    @Test
    fun `2 to 3 turns the version 2 readings table into the version 3 one`() {
        fun columns(version: Int): List<String> {
            val ddl = canonicalStatements(version)
                .first { it.startsWith("CREATE TABLE IF NOT EXISTS `vitals_readings`") }
            return ddl.substringAfter("(").substringBeforeLast(")")
                .split(", ").map { it.trim() }
        }

        val added = GoneMigrations.MIGRATION_2_3_STATEMENTS
            .filter { it.startsWith("ALTER TABLE `vitals_readings` ADD COLUMN ") }
            .map { it.removePrefix("ALTER TABLE `vitals_readings` ADD COLUMN ") }

        assertEquals(GoneMigrations.MIGRATION_2_3_READING_COLUMNS.size, added.size)
        assertEquals(columns(3), columns(2) + added)
    }

    /** A NOT NULL column without a default would make ADD COLUMN fail on existing rows. */
    @Test
    fun `columns added to existing rows are nullable`() {
        GoneMigrations.MIGRATION_2_3_STATEMENTS
            .filter { it.contains("ADD COLUMN") }
            .forEach { assertFalse("added column must be nullable: $it", it.uppercase().contains("NOT NULL")) }
    }

    @Test
    fun `2 to 3 is purely additive`() {
        val destructive = listOf("DROP TABLE", "DROP INDEX", "DELETE FROM", "TRUNCATE", "DROP COLUMN", "RENAME")
        GoneMigrations.MIGRATION_2_3_STATEMENTS.forEach { stmt ->
            destructive.forEach { keyword ->
                assertFalse("destructive statement found: $stmt", stmt.uppercase().contains(keyword))
            }
        }
        val altered = GoneMigrations.MIGRATION_2_3_STATEMENTS
            .mapNotNull { Regex("ALTER TABLE `([^`]+)`").find(it)?.groupValues?.get(1) }
            .toSet()
        assertEquals("only vitals_readings may be altered", setOf("vitals_readings"), altered)
    }

    // ── 3 -> 4 ───────────────────────────────────────────────────────────────

    @Test
    fun `version 4 schema is exported`() {
        assertTrue(schemaFile(4).readText().contains("\"version\": 4"))
    }

    /** The version 3 library table plus the added column must be exactly Room's version 4 table. */
    @Test
    fun `3 to 4 turns the version 3 library table into the version 4 one`() {
        fun columns(version: Int): List<String> {
            val ddl = canonicalStatements(version)
                .first { it.startsWith("CREATE TABLE IF NOT EXISTS `library_entries`") }
            return ddl.substringAfter("(").substringBeforeLast(")").split(", ").map { it.trim() }
        }
        val added = GoneMigrations.MIGRATION_3_4_STATEMENTS
            .map { it.removePrefix("ALTER TABLE `library_entries` ADD COLUMN ") }
        assertEquals(columns(3) + added, columns(4))
    }

    /** Existing entries must read as not shared: nothing saved goes to the model unasked. */
    @Test
    fun `3 to 4 defaults every existing entry to not shared`() {
        val statement = GoneMigrations.MIGRATION_3_4_STATEMENTS.single()
        assertTrue(statement.endsWith("NOT NULL DEFAULT 0"))
    }

    @Test
    fun `3 to 4 is purely additive and touches only the library table`() {
        val destructive = listOf("DROP", "DELETE FROM", "TRUNCATE", "RENAME", "UPDATE")
        GoneMigrations.MIGRATION_3_4_STATEMENTS.forEach { stmt ->
            destructive.forEach { assertFalse("destructive statement found: $stmt", stmt.uppercase().contains(it)) }
            assertTrue(stmt.startsWith("ALTER TABLE `library_entries` ADD COLUMN "))
        }
    }

    /** Every other table is unchanged between versions 3 and 4. */
    @Test
    fun `version 4 changes no other table`() {
        val v3 = canonicalStatements(3).map { normalise(it) }.filterNot { it.contains("`library_entries`") }.toSet()
        val v4 = canonicalStatements(4).map { normalise(it) }.filterNot { it.contains("`library_entries`") }.toSet()
        assertEquals(v3, v4)
    }

    // ── 4 -> 5 ───────────────────────────────────────────────────────────────

    @Test
    fun `version 5 schema is exported`() {
        assertTrue(schemaFile(5).readText().contains("\"version\": 5"))
    }

    /**
     * The version 5 emergency_profiles table must match Room's canonical DDL.
     */
    @Test
    fun `4 to 5 creates the emergency_profiles table matching Room version 5`() {
        val canonical = canonicalStatements(5)
            .first { it.startsWith("CREATE TABLE IF NOT EXISTS `emergency_profiles`") }
        val migration = GoneMigrations.MIGRATION_4_5_STATEMENTS
            .first { it.startsWith("CREATE TABLE IF NOT EXISTS `emergency_profiles`") }
        assertEquals(normalise(canonical), normalise(migration))
    }

    @Test
    fun `4 to 5 is purely additive and introduces only the emergency_profiles table`() {
        val destructive = listOf("DROP TABLE", "DROP INDEX", "DELETE FROM", "TRUNCATE", "DROP COLUMN", "RENAME")
        GoneMigrations.MIGRATION_4_5_STATEMENTS.forEach { stmt ->
            destructive.forEach { assertFalse("destructive statement found: $stmt", stmt.uppercase().contains(it)) }
            assertTrue(normalise(stmt).startsWith("CREATE TABLE IF NOT EXISTS `emergency_profiles`"))
        }
    }

    /** Every other table is unchanged between versions 4 and 5. */
    @Test
    fun `version 5 changes no other table`() {
        val v4 = canonicalStatements(4).map { normalise(it) }.toSet()
        val v5 = canonicalStatements(5).map { normalise(it) }.filterNot { it.contains("`emergency_profiles`") }.toSet()
        assertEquals(v4, v5)
    }

    /** Migrations must be contiguous or Room cannot find a path between versions. */
    @Test
    fun `registered migrations form an unbroken chain`() {
        val sorted = GoneMigrations.ALL.sortedBy { it.startVersion }
        sorted.zipWithNext().forEach { (a, b) ->
            assertEquals(
                "gap between migration ${a.startVersion}->${a.endVersion} and " +
                    "${b.startVersion}->${b.endVersion}",
                a.endVersion, b.startVersion
            )
        }
        assertEquals("chain must start at version 1", 1, sorted.first().startVersion)
    }
}
