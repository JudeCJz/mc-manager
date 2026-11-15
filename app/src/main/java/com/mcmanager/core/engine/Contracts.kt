package com.mcmanager.core.engine

import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.ConsoleLine
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.core.model.JavaRuntime
import com.mcmanager.core.model.RuntimeState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface JavaRuntimeManager {
  fun installed(): List<JavaRuntime>

  /** Downloads the runtime if missing. */
  suspend fun ensure(major: Int, onProgress: (InstallProgress) -> Unit = {}): JavaRuntime

  suspend fun remove(major: Int)

  /** Java binary for a server: customJavaPath > settings.globalJavaPath > managed runtime (ensure). */
  suspend fun javaFor(server: ServerEntity, onProgress: (InstallProgress) -> Unit = {}): File
}

interface VersionRepository {
  /** Newest first. */
  suspend fun mcVersions(engine: Engine, includeSnapshots: Boolean = false): List<String>

  /** Build id / loader version. */
  suspend fun latestBuild(engine: Engine, mcVersion: String): String?

  suspend fun requiredJava(mcVersion: String): Int
}

interface ServerInstaller {
  /** Downloads/installs jar for (engine, mcVersion, build). Writes into server.dir. Returns launchSpec. */
  suspend fun install(server: ServerEntity, build: String?, onProgress: (InstallProgress) -> Unit): String

  /** Checks whether a newer build/version exists. null = up to date / unknown. */
  suspend fun updateAvailable(server: ServerEntity): String?
}

interface ServerController {
  /** Only servers that were touched; missing = STOPPED. */
  val runtimes: StateFlow<Map<Long, RuntimeState>>

  fun runtime(id: Long): StateFlow<RuntimeState>

  /** Ring buffer, newest last. */
  fun console(id: Long): StateFlow<List<ConsoleLine>>

  /** Ensures java, eula.txt, server.properties port, starts service. */
  suspend fun start(id: Long): Result<Unit>

  /** Sends "stop", waits up to 30s, then kills. */
  suspend fun stop(id: Long)

  suspend fun restart(id: Long)

  fun kill(id: Long)

  /** No leading slash needed; echoes as CMD line. */
  fun sendCommand(id: Long, command: String)

  suspend fun stopAll()
}
