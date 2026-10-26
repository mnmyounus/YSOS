package com.mnmyounus.ysos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [EmergencyContact::class],
    version = 1,
    exportSchema = false
)
abstract class EmergencyDatabase : RoomDatabase() {

    abstract fun emergencyDao(): EmergencyDao

    companion object {
        @Volatile
        private var INSTANCE: EmergencyDatabase? = null

        fun getDatabase(context: Context): EmergencyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EmergencyDatabase::class.java,
                    "ysos_emergency_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                // Seed on first run
                CoroutineScope(Dispatchers.IO).launch {
                    if (instance.emergencyDao().getCount() == 0) {
                        CountryDataSeeder.seed(context, instance.emergencyDao())
                    }
                }
                instance
            }
        }
    }
}
