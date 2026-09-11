package com.infinity.ai.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.infinity.ai.health.data.AnomalyDao
import com.infinity.ai.health.data.AnomalyEventEntity
import com.infinity.ai.health.data.DeviceDao
import com.infinity.ai.health.data.DeviceEntity
import com.infinity.ai.health.data.GoneMigrations
import com.infinity.ai.health.data.PatientDao
import com.infinity.ai.health.data.PatientEntity
import com.infinity.ai.health.data.VitalsDao
import com.infinity.ai.health.data.VitalsReadingEntity

/**
 * The single G-one database.
 *
 * Version 2 adds the health record — patients, devices, vitals readings, and
 * anomaly events — alongside the inherited library tables via a purely additive
 * migration. See [GoneMigrations] for why this is a real migration rather than
 * `fallbackToDestructiveMigration()`.
 *
 * FILE NAME: the on-disk name stays `infinity_library.db` deliberately. Renaming
 * the file would orphan every existing install's data, which is a strictly worse
 * outcome than a filename that no longer matches the product name. The class was
 * renamed from `LibraryDatabase`; the file was not.
 *
 * SCHEMA HISTORY: `exportSchema = true` writes the canonical schema to
 * `app/schemas/`, and both `1.json` and `2.json` are committed. `1.json` was
 * captured by temporarily pinning this class back to version 1, because the
 * original v1 shipped with `exportSchema = false` and therefore left no record.
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
        AnomalyEventEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class GoneDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    abstract fun patientDao(): PatientDao
    abstract fun deviceDao(): DeviceDao
    abstract fun vitalsDao(): VitalsDao
    abstract fun anomalyDao(): AnomalyDao

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
