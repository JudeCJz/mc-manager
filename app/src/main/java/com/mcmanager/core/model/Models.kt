package com.mcmanager.core.model

import java.io.File

enum class Engine(
  val label: String,
  val supported: Boolean,
  val experimental: Boolean,
  val isModLoader: Boolean,
) {
  PAPER("Paper", true, false, false),
  PURPUR("Purpur", true, false, false),
  FABRIC("Fabric", true, false, true),
  FORGE("Forge", true, true, true),
  VANILLA("Vanilla", true, false, false),
  SPIGOT("Spigot", false, false, false),
}

enum class ServerState { STOPPED, INSTALLING, STARTING, RUNNING, STOPPING, CRASHED }

data class RuntimeState(
  val state: ServerState = ServerState.STOPPED,
  val pid: Long? = null,
  val startedAt: Long? = null, // epoch ms
  val cpuPercent: Float? = null, // process CPU % of one core * (0..cores*100)
  val rssMb: Int? = null,
  val tps: Double? = null, // null if engine can't report
  val onlinePlayers: List<String> = emptyList(),
  val maxPlayers: Int? = null,
  val lastExitCode: Int? = null,
  val message: String? = null, // e.g. install progress text or crash reason
  val progress: Float? = null, // 0..1 during INSTALLING
)

data class ConsoleLine(val seq: Long, val time: Long, val text: String, val kind: Kind) {
  /** CMD = echoed user command, SYSTEM = manager messages. */
  enum class Kind { OUT, ERR, CMD, SYSTEM }
}

/** [fraction] null = indeterminate. */
data class InstallProgress(val step: String, val fraction: Float?)

data class JavaRuntime(val major: Int, val home: File, val javaBin: File, val versionString: String)
