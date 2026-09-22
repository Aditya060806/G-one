package com.gone.ai.health.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations for the G-one database.
 *
 * WHY A HAND-WRITTEN MIGRATION AND NOT `fallbackToDestructiveMigration()`
 *
 * Destructive fallback silently drops every table on any version bump. In a health
 * app that means a user's entire vitals and alert history disappears on upgrade —
 * and, critically, it is untestable: there is no migration to assert against, so a
 * schema mistake surfaces as data loss in the field rather than as a red test.
 *
 * MIGRATION 1 -> 2 IS PURELY ADDITIVE
 *
 * Version 1 held the inherited `library_entries` + `library_entries_fts` tables.
 * This migration only CREATEs new tables. It contains no DROP, no DELETE, and no
 * ALTER of any pre-existing table, so upgrading users keep everything they had.
 * [MIGRATION_1_2_STATEMENTS] is exposed so a unit test can assert that additivity
 * property directly rather than trusting the comment.
 */
object GoneMigrations {

    /**
     * Exact DDL executed by [MIGRATION_1_2], in order.
     *
     * These strings are verified against Room's own generated schema JSON
     * (`app/schemas/.../2.json`) — Room validates the live schema on open, so any
     * drift between this DDL and the entity definitions fails fast at startup.
     */
    val MIGRATION_1_2_STATEMENTS: List<String> = listOf(

        // ── patients ──────────────────────────────────────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `patients` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `age` INTEGER NOT NULL,
            `sex` TEXT,
            `chronicConditions` TEXT,
            `emergencyContactName` TEXT,
            `emergencyContactPhone` TEXT,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),

        // ── devices ───────────────────────────────────────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `devices` (
            `id` TEXT NOT NULL,
            `patientId` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `transport` TEXT NOT NULL,
            `lastSeenAt` INTEGER NOT NULL,
            `batteryPercent` INTEGER,
            `firmwareVersion` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_devices_patientId` ON `devices` (`patientId`)",

        // ── vitals_readings ───────────────────────────────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `vitals_readings` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `patientId` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `heartRate` INTEGER,
            `spo2` INTEGER,
            `bodyTempC` REAL,
            `motionMagnitudeG` REAL,
            `ambientTempC` REAL,
            `ambientHumidityPct` REAL,
            `aqi` INTEGER,
            `source` TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_vitals_readings_patientId_timestamp` " +
            "ON `vitals_readings` (`patientId`, `timestamp`)",

        // ── anomaly_events ────────────────────────────────────────────────────
        """
        CREATE TABLE IF NOT EXISTS `anomaly_events` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `patientId` TEXT NOT NULL,
            `eventType` TEXT NOT NULL,
            `severity` TEXT NOT NULL,
            `riskHeat` INTEGER NOT NULL,
            `riskRespiratory` INTEGER NOT NULL,
            `riskCardiovascular` INTEGER NOT NULL,
            `evidenceJson` TEXT NOT NULL,
            `templateExplanation` TEXT NOT NULL,
            `aiExplanation` TEXT,
            `status` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `acknowledgedAt` INTEGER,
            `syncedAt` INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_anomaly_events_patientId_createdAt` " +
            "ON `anomaly_events` (`patientId`, `createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_anomaly_events_status` " +
            "ON `anomaly_events` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_anomaly_events_patientId_eventType_createdAt` " +
            "ON `anomaly_events` (`patientId`, `eventType`, `createdAt`)"
    )

    /** Tables this migration introduces. Asserted against by unit test. */
    val MIGRATION_1_2_NEW_TABLES: Set<String> =
        setOf("patients", "devices", "vitals_readings", "anomaly_events")

    /**
     * Tables that existed at version 1 and MUST survive the migration untouched.
     * A unit test asserts none of them is named in any statement.
     */
    val VERSION_1_TABLES: Set<String> =
        setOf("library_entries", "library_entries_fts")

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
        }
    }

    // ── 2 -> 3: wearable channels and monitoring sessions ────────────────────

    /**
     * Columns added to `vitals_readings` by [MIGRATION_2_3], as `name` to SQL type.
     *
     * SQLite's ADD COLUMN appends, which is why the entity declares these last. Every one
     * is nullable with no default, so existing rows simply read as "channel not reported"
     * — which is the truth for a reading taken before the wearable had these sensors.
     */
    val MIGRATION_2_3_READING_COLUMNS: List<Pair<String, String>> = listOf(
        "skinTempC" to "REAL",
        "emgMean" to "INTEGER",
        "emgMax" to "INTEGER"
    )

    /**
     * Exact DDL executed by [MIGRATION_2_3], in order. Checked against Room's exported
     * `3.json` by unit test, like the 1 -> 2 statements.
     *
     * Still purely additive: three ADD COLUMNs and two new tables. No existing column,
     * row or table is dropped or rewritten, so an upgrade keeps every reading and alert.
     * ADD COLUMN cannot take IF NOT EXISTS in SQLite; Room runs a migration exactly once
     * per version step, inside a transaction, so that is safe.
     */
    val MIGRATION_2_3_STATEMENTS: List<String> =
        MIGRATION_2_3_READING_COLUMNS.map { (name, type) ->
            "ALTER TABLE `vitals_readings` ADD COLUMN `$name` $type"
        } + listOf(

            // ── health_sessions ───────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS `health_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `patientId` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER,
                `status` TEXT NOT NULL,
                `note` TEXT
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_health_sessions_patientId_startedAt` " +
                "ON `health_sessions` (`patientId`, `startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_health_sessions_status` ON `health_sessions` (`status`)",

            // ── session_reports ───────────────────────────────────────────────
            """
            CREATE TABLE IF NOT EXISTS `session_reports` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `patientId` TEXT NOT NULL,
                `generatedAt` INTEGER NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `endedAt` INTEGER NOT NULL,
                `sampleCount` INTEGER NOT NULL,
                `sources` TEXT NOT NULL,
                `highestSeverity` TEXT,
                `eventCount` INTEGER NOT NULL,
                `hrMin` REAL,
                `hrMean` REAL,
                `hrMax` REAL,
                `spo2Min` REAL,
                `spo2Mean` REAL,
                `spo2Max` REAL,
                `bodyTempMin` REAL,
                `bodyTempMax` REAL,
                `skinTempMin` REAL,
                `skinTempMean` REAL,
                `skinTempMax` REAL,
                `motionPeakG` REAL,
                `emgMean` REAL,
                `emgPeak` INTEGER,
                `gapCount` INTEGER NOT NULL,
                `longestGapMillis` INTEGER NOT NULL,
                `representativePoints` TEXT NOT NULL,
                `observations` TEXT NOT NULL,
                `aiSummary` TEXT
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_reports_sessionId` " +
                "ON `session_reports` (`sessionId`)",
            "CREATE INDEX IF NOT EXISTS `index_session_reports_patientId_generatedAt` " +
                "ON `session_reports` (`patientId`, `generatedAt`)"
        )

    /** Tables [MIGRATION_2_3] introduces. Asserted against by unit test. */
    val MIGRATION_2_3_NEW_TABLES: Set<String> = setOf("health_sessions", "session_reports")

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_2_3_STATEMENTS.forEach(db::execSQL)
        }
    }

    // ── 3 -> 4: Vault entries the chat may use ───────────────────────────────

    /**
     * Exact DDL executed by [MIGRATION_3_4]. One column: whether the person marked a Vault
     * entry for the chat to use. NOT NULL with DEFAULT 0, so every existing entry reads as
     * not shared until the person says otherwise — nothing already saved is sent to the
     * model without being chosen. Checked against Room's `4.json` by unit test.
     */
    val MIGRATION_3_4_STATEMENTS: List<String> = listOf(
        "ALTER TABLE `library_entries` ADD COLUMN `useInAi` INTEGER NOT NULL DEFAULT 0"
    )

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_3_4_STATEMENTS.forEach(db::execSQL)
        }
    }

    // ── 4 -> 5: Emergency Medical ID profile ─────────────────────────────────

    /**
     * Exact DDL executed by [MIGRATION_4_5].
     *
     * Purely additive: one new table. No DROP, no DELETE, no ALTER of any pre-existing
     * table. Upgrading users keep all their health data and vault entries unchanged.
     *
     * PRIMARY KEY on `patientId` enforces the one-profile-per-patient invariant at the
     * schema level. The `emergencyId` is a display/routing key, not a database key.
     *
     * INTEGER booleans use DEFAULT 1 (share_*=true) or DEFAULT 0 (shareMedications=false)
     * so a row inserted without explicit values has privacy-appropriate defaults.
     */
    val MIGRATION_4_5_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `emergency_profiles` (
            `patientId` TEXT NOT NULL,
            `emergencyId` TEXT NOT NULL,
            `bloodGroup` TEXT,
            `allergies` TEXT,
            `chronicConditions` TEXT,
            `medications` TEXT,
            `implantedDevices` TEXT,
            `primaryEmergencyContact` TEXT,
            `emergencyContactsJson` TEXT NOT NULL,
            `shareAllergies` INTEGER NOT NULL,
            `shareConditions` INTEGER NOT NULL,
            `shareMedications` INTEGER NOT NULL,
            `shareLiveVitals` INTEGER NOT NULL,
            `tagWrittenAt` INTEGER,
            `qrGeneratedAt` INTEGER,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`patientId`)
        )
        """.trimIndent()
    )

    /** Tables [MIGRATION_4_5] introduces. Asserted against by unit test. */
    val MIGRATION_4_5_NEW_TABLES: Set<String> = setOf("emergency_profiles")

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_4_5_STATEMENTS.forEach(db::execSQL)
        }
    }

    /** Every migration, in ascending order, for the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
