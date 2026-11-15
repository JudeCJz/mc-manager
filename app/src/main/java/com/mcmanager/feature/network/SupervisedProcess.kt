package com.mcmanager.feature.network

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a child process and restarts it with exponential backoff when it exits, until [stop].
 * stdout+stderr are merged and delivered line by line to [onLine].
 */
class SupervisedProcess(
  private val builder: () -> ProcessBuilder?,
  private val onLine: (String) -> Unit,
  private val onPhase: (Phase, String?) -> Unit,
) {
  enum class Phase { STARTING, RUNNING, BACKOFF, STOPPED, FAILED_TO_START }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null

  @Volatile private var process: Process? = null

  val running: Boolean get() = job?.isActive == true

  @Synchronized
  fun start() {
    if (job?.isActive == true) return
    job = scope.launch {
      var backoff = 2_000L
      while (isActive) {
        val pb = builder()
        if (pb == null) {
          onPhase(Phase.FAILED_TO_START, null)
          return@launch
        }
        pb.redirectErrorStream(true)
        onPhase(Phase.STARTING, null)
        val began = System.currentTimeMillis()
        val p = try {
          pb.start()
        } catch (e: Exception) {
          onPhase(Phase.FAILED_TO_START, e.message)
          return@launch
        }
        process = p
        launch {
          delay(4_000)
          if (p.isAlive) onPhase(Phase.RUNNING, null)
        }
        try {
          withContext(Dispatchers.IO) {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
              while (true) {
                val line = r.readLine() ?: break
                onLine(line)
              }
            }
          }
          val code = p.waitFor()
          if (!isActive) break
          if (System.currentTimeMillis() - began > 60_000) backoff = 2_000L
          onPhase(Phase.BACKOFF, "exit $code, retry in ${backoff / 1000}s")
          delay(backoff)
          backoff = (backoff * 2).coerceAtMost(60_000L)
        } finally {
          killProcess(p)
          process = null
        }
      }
    }
  }

  fun stop() {
    val j = job
    job = null
    process?.let { killProcess(it) }
    j?.cancel()
    onPhase(Phase.STOPPED, null)
  }

  private fun killProcess(p: Process) {
    try {
      p.destroy()
      Thread {
        try {
          Thread.sleep(3000)
          if (p.isAlive) p.destroyForcibly()
        } catch (_: Exception) {
        }
      }.start()
    } catch (_: Exception) {
    }
  }
}
