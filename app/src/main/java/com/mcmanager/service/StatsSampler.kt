package com.mcmanager.service

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Samples CPU and RSS of a process from /proc every 2 s. */
object StatsSampler {
  private const val CLK_TCK = 100.0

  /** utime+stime in clock ticks from the contents of /proc/<pid>/stat. */
  fun parseCpuTicks(stat: String): Long? {
    val rest = stat.substringAfterLast(')', "").trim().split(' ')
    // after ')' : state(0) ppid(1) ... utime is field 14 -> index 11, stime index 12
    val u = rest.getOrNull(11)?.toLongOrNull() ?: return null
    val s = rest.getOrNull(12)?.toLongOrNull() ?: return null
    return u + s
  }

  /** VmRSS in MB from the contents of /proc/<pid>/status. */
  fun parseRssMb(status: String): Int? {
    val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
    val kb = line.removePrefix("VmRSS:").trim().split(' ').firstOrNull()?.toLongOrNull() ?: return null
    return (kb / 1024).toInt()
  }

  /** Runs until cancelled. Reports null values when /proc cannot be read. */
  suspend fun run(pid: Long, onSample: (cpuPercent: Float?, rssMb: Int?) -> Unit) {
    var lastTicks: Long? = null
    var lastTime = 0L
    while (coroutineContext.isActive) {
      delay(2000)
      val (cpu, rss) = withContext(Dispatchers.IO) {
        val ticks = runCatching { File("/proc/$pid/stat").readText() }.getOrNull()?.let { parseCpuTicks(it) }
        val now = System.nanoTime()
        var cpuP: Float? = null
        if (ticks != null) {
          val prev = lastTicks
          if (prev != null && now > lastTime) {
            val elapsed = (now - lastTime) / 1e9
            cpuP = (((ticks - prev) / CLK_TCK) / elapsed * 100.0).toFloat().coerceAtLeast(0f)
          }
          lastTicks = ticks
          lastTime = now
        }
        val rssMb = runCatching { File("/proc/$pid/status").readText() }.getOrNull()?.let { parseRssMb(it) }
        cpuP to rssMb
      }
      onSample(cpu, rss)
    }
  }
}
