package com.mcmanager.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [ServerEntity::class, BackupEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun serverDao(): ServerDao
  abstract fun backupDao(): BackupDao
}
