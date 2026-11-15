package com.mcmanager.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
  @Query("SELECT * FROM servers ORDER BY createdAt")
  fun observeAll(): Flow<List<ServerEntity>>

  @Query("SELECT * FROM servers WHERE id=:id")
  fun observe(id: Long): Flow<ServerEntity?>

  @Query("SELECT * FROM servers WHERE id=:id")
  suspend fun get(id: Long): ServerEntity?

  @Query("SELECT * FROM servers")
  suspend fun getAll(): List<ServerEntity>

  @Insert
  suspend fun insert(s: ServerEntity): Long

  @Update
  suspend fun update(s: ServerEntity)

  @Delete
  suspend fun delete(s: ServerEntity)
}

@Dao
interface BackupDao {
  @Query("SELECT * FROM backups WHERE serverId=:sid ORDER BY createdAt DESC")
  fun observeFor(sid: Long): Flow<List<BackupEntity>>

  @Query("SELECT * FROM backups WHERE serverId=:sid ORDER BY createdAt DESC")
  suspend fun listFor(sid: Long): List<BackupEntity>

  @Insert
  suspend fun insert(b: BackupEntity): Long

  @Delete
  suspend fun delete(b: BackupEntity)
}
