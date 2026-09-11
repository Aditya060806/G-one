package com.infinity.ai.health.data

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

    /** Every migration, in ascending order, for the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
