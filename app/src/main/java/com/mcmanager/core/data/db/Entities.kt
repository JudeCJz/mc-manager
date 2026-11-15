package com.mcmanager.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mcmanager.core.model.Engine

@Entity(tableName = "servers")
data class ServerEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val engine: Engine,
  val mcVersion: String,
  val engineBuild: String? = null, // paper/purpur build number, fabric loader ver, forge ver
  val dir: String, // absolute path of server directory
  val launchSpec: String? = null, // e.g. "server.jar" or "@libraries/.../unix_args.txt"; null = not installed
  val port: Int = 25565,
  val xmxMb: Int = 3072,
  val xmsMb: Int = 1024,
  val jvmPreset: String = "aikar", // id from res/raw/jvm_presets.json
  val gc: String = "G1", // G1 | ZGC | Shenandoah | Parallel | Serial
  val extraJvmArgs: String = "",
  val javaMajor: Int = 21,
  val customJavaPath: String? = null, // overrides managed runtime
  val eulaAccepted: Boolean = false,
  val autoRestart: Boolean = true,
  val backupIntervalHours: Int = 0, // 0 = off
  val backupKeep: Int = 5,
  val seed: String = "",
  val gamemode: String = "survival",
  val createdAt: Long = System.currentTimeMillis(),
  val lastStartedAt: Long? = null,
)

@Entity(tableName = "backups")
data class BackupEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverId: Long,
  val file: String,
  val sizeBytes: Long,
  val createdAt: Long,
  val label: String,
  val auto: Boolean,
)
