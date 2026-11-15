package com.mcmanager.feature.server

import java.util.Locale
import kotlin.math.roundToInt

/** Uptime like "2d 3h", "4h 12m", "5m 09s", "12s"; "–" when [startedAt] is null. */
fun formatUptime(startedAt: Long?, now: Long = System.currentTimeMillis()): String {
  if (startedAt == null) return "–"
  val s = ((now - startedAt) / 1000).coerceAtLeast(0)
  val d = s / 86400
  val h = s % 86400 / 3600
  val m = s % 3600 / 60
  val sec = s % 60
  return when {
    d > 0 -> "${d}d ${h}h"
    h > 0 -> "${h}h ${m}m"
    m > 0 -> "${m}m ${"%02d".format(sec)}s"
    else -> "${sec}s"
  }
}

/** 512 -> "512 MB", 3072 -> "3 GB", 1536 -> "1.5 GB". */
fun formatMb(mb: Int): String {
  if (mb < 1024) return "$mb MB"
  val gb = mb / 1024.0
  return if (mb % 1024 == 0) "${mb / 1024} GB" else String.format(Locale.US, "%.1f GB", gb)
}

fun formatBytes(bytes: Long): String = when {
  bytes < 1024 -> "$bytes B"
  bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
  bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
  else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

fun formatTps(tps: Double?): String = if (tps == null) "–" else String.format(Locale.US, "%.1f", tps.coerceAtMost(20.0))

fun formatCpu(cpu: Float?): String = if (cpu == null) "–" else "${cpu.roundToInt()}%"

enum class RamLevel { OK, WARN, DANGER }

/** WARN above 50 % of phone RAM, DANGER above 65 %. */
fun ramLevel(usedMb: Int, totalMb: Int): RamLevel {
  if (totalMb <= 0) return RamLevel.OK
  val ratio = usedMb.toDouble() / totalMb
  return when {
    ratio > 0.65 -> RamLevel.DANGER
    ratio > 0.50 -> RamLevel.WARN
    else -> RamLevel.OK
  }
}

/** Suggests the next free server port starting at 25565. */
fun nextFreePort(used: Collection<Int>): Int {
  var p = 25565
  while (p in used) p++
  return p
}
