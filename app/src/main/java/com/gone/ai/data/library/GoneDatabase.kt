package com.gone.ai.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.gone.ai.health.data.AnomalyDao
import com.gone.ai.health.data.AnomalyEventEntity
import com.gone.ai.health.data.DeviceDao
import com.gone.ai.health.data.DeviceEntity
import com.gone.ai.health.data.EmergencyDao
import com.gone.ai.health.data.EmergencyProfileEntity
import com.gone.ai.health.data.GoneMigrations
import com.gone.ai.health.data.HealthSessionEntity
import com.gone.ai.health.data.PatientDao
import com.gone.ai.health.data.PatientEntity
import com.gone.ai.health.data.SessionDao
import com.gone.ai.health.data.SessionReportDao
import com.gone.ai.health.data.SessionReportEntity
import com.gone.ai.health.data.VitalsDao
import com.gone.ai.health.data.VitalsReadingEntity

/**
 * The single G-one database.
 *
 * Version 2 adds the health record — patients, devices, vitals readings, and
 * anomaly events — alongside the inherited library tables via a purely additive
 * migration. Version 3 adds the wearable's skin-temperature and EMG channels and
 * monitoring sessions with their reports, again purely additively. Version 4 adds one
 * column marking the Vault entries the chat may use. Version 5 adds the Emergency
 * Medical ID profile table (see [GoneMigrations.MIGRATION_4_5]). See
 * [GoneMigrations] for why these are real migrations rather than
 * `fallbackToDestructiveMigration()`.
 *
 * FILE NAME: the on-disk name stays `infinity_library.db` deliberately. Renaming
 * the file would orphan every existing install's data, which is a strictly worse
 * outcome than a filename that no longer matches the product name. The class was
 * renamed from `LibraryDatabase`; the file was not.
 *
 * SCHEMA HISTORY: `exportSchema = true` writes the canonical schema to
 * `app/schemas/`, and `1.json`, `2.json` and `3.json` are kept in the repository.
 * `1.json` was captured by temporarily pinning this class back to version 1, because
 * the original v1 shipped with `exportSchema = false` and therefore left no record.
 * Without it the 1 -> 2 migration could not be exercised from a real v1 database
 * at all — Room's MigrationTestHelper needs the old schema to build one.
 */
@TypeConverters(EntryTypeConverter::class)
@Database(
    entities = [
        // v1 — inherited library
        LibraryEntry::class,
        LibraryEntryFts::class,
        // v2 — health record
        PatientEntity::class,
        DeviceEntity::class,
        VitalsReadingEntity::class,
        AnomalyEventEntity::class,
        // v3 — monitoring sessions and their reports
        HealthSessionEntity::class,
        SessionReportEntity::class,
        // v5 — emergency medical ID profile
        EmergencyProfileEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class GoneDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    abstract fun patientDao(): PatientDao
    abstract fun deviceDao(): DeviceDao
    abstract fun vitalsDao(): VitalsDao
    abstract fun anomalyDao(): AnomalyDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionReportDao(): SessionReportDao
    abstract fun emergencyDao(): EmergencyDao

    companion object {
        const val DB_NAME = "infinity_library.db"

        @Volatile private var INSTANCE: GoneDatabase? = null

        fun getInstance(context: Context): GoneDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GoneDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*GoneMigrations.ALL)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

class EntryTypeConverter {
    @TypeConverter fun fromEntryType(type: EntryType): String = type.name
    @TypeConverter fun toEntryType(name: String): EntryType   = EntryType.valueOf(name)
}
