package com.mcmanager.service

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One running server child process. Reads the merged stdout/stderr on IO, reports each line via
 * [onLine] and the exit via [onExit] (called before [awaitExit] completes).
 */
class ServerProcess(
  private val process: Process,
  scope: CoroutineScope,
  javaBin: File,
  private val onLine: (String) -> Unit,
  private val onExit: (code: Int, stopRequested: Boolean) -> Unit,
) {
  @Volatile var stopRequested = false
    private set
  val pid: Long? = pidOf(process, javaBin)
  private val exited = CompletableDeferred<Int>()
  private val stdin = process.outputStream.bufferedWriter()

  init {
    scope.launch(Dispatchers.IO) {
      try {
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { onLine(it) }
      } catch (_: IOException) {
        // stream closed on exit
      }
      val code = try {
        process.waitFor()
      } catch (_: InterruptedException) {
        -1
      }
      try {
        onExit(code, stopRequested)
      } finally {
        exited.complete(code)
      }
    }
  }

  val isAlive: Boolean get() = !exited.isCompleted

  /** Writes a line to the server's stdin. Returns false if the pipe is closed. */
  @Synchronized
  fun writeLine(line: String): Boolean = try {
    stdin.write(line)
    stdin.write("\n")
    stdin.flush()
    true
  } catch (_: IOException) {
    false
  }

  /** Sends "stop", waits up to 30 s, then destroy(), then destroyForcibly() after 5 s. */
  suspend fun stopGracefully(onForce: () -> Unit = {}) {
    stopRequested = true
    writeLine("stop")
    if (withTimeoutOrNull(30_000) { exited.await() } != null) return
    onForce()
    process.destroy()
    if (withTimeoutOrNull(5_000) { exited.await() } != null) return
    process.destroyForcibly()
    withTimeoutOrNull(5_000) { exited.await() }
  }

  fun kill() {
    stopRequested = true
    process.destroyForcibly()
  }

  suspend fun awaitExit(): Int = exited.await()

  companion object {
    /** java.lang.Process has no pid() on all Android versions: try reflection, then /proc children. */
    fun pidOf(p: Process, javaBin: File): Long? {
      runCatching {
        val m = p.javaClass.getMethod("pid")
        (m.invoke(p) as? Number)?.toLong()
      }.getOrNull()?.let { return it }
      runCatching {
        var c: Class<*>? = p.javaClass
        while (c != null) {
          val f = runCatching { c!!.getDeclaredField("pid") }.getOrNull()
          if (f != null) {
            f.isAccessible = true
            return (f.get(p) as Number).toLong()
          }
          c = c.superclass
        }
      }
      return childPidFromProc(javaBin)
    }

    private fun childPidFromProc(javaBin: File): Long? = runCatching {
      val bin = javaBin.absolutePath
      File("/proc/self/task").listFiles().orEmpty().asSequence()
        .flatMap { t -> runCatching { File(t, "children").readText().trim().split(' ') }.getOrDefault(emptyList()).asSequence() }
        .mapNotNull { it.toLongOrNull() }
        .filter { pid ->
          runCatching { File("/proc/$pid/cmdline").readText().substringBefore('\u0000') }.getOrNull()
            ?.let { it == bin || it.endsWith("/java") } == true
        }
        .lastOrNull()
    }.getOrNull()
  }
}
