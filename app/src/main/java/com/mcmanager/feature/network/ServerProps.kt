package com.mcmanager.feature.network

import java.io.File

/** Minimal, line-preserving editor for `server.properties` (pure functions + small file helpers). */
object ServerProps {

  data class Change(val key: String, val old: String?, val new: String)

  /** Low-latency settings offered by the Network screen. */
  val LOW_LATENCY: Map<String, String> = linkedMapOf(
    "network-compression-threshold" to "256",
    "view-distance" to "8",
    "simulation-distance" to "6",
    "sync-chunk-writes" to "false",
  )

  private fun keyOf(line: String): String? {
    val t = line.trimStart()
    if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return null
    val i = t.indexOf('=')
    if (i <= 0) return null
    return t.substring(0, i).trim().ifEmpty { null }
  }

  fun parse(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lines()) {
      val k = keyOf(line) ?: continue
      out[k] = line.substring(line.indexOf('=') + 1).trim()
    }
    return out
  }

  /** Changes needed to bring [text] to [wanted]; entries already equal are omitted. */
  fun diff(text: String, wanted: Map<String, String>): List<Change> {
    val cur = parse(text)
    return wanted.mapNotNull { (k, v) -> if (cur[k] == v) null else Change(k, cur[k], v) }
  }

  /** Replaces existing keys in place (keeping comments/order) and appends missing keys. */
  fun apply(text: String, changes: Map<String, String>): String {
    val nl = if (text.contains("\r\n")) "\r\n" else "\n"
    val remaining = LinkedHashMap(changes)
    val lines = if (text.isEmpty()) mutableListOf() else text.split("\r\n", "\n").toMutableList()
    var trailingEmpty = false
    if (lines.isNotEmpty() && lines.last().isEmpty()) {
      lines.removeAt(lines.size - 1)
      trailingEmpty = true
    }
    for (i in lines.indices) {
      val k = keyOf(lines[i]) ?: continue
      val v = remaining.remove(k) ?: continue
      lines[i] = "$k=$v"
    }
    for ((k, v) in remaining) lines.add("$k=$v")
    return lines.joinToString(nl) + (if (trailingEmpty || text.isEmpty()) nl else "")
  }

  fun file(serverDir: String) = File(serverDir, "server.properties")

  fun read(serverDir: String): String = file(serverDir).let { if (it.isFile) it.readText() else "" }

  fun levelName(serverDir: String): String =
    parse(read(serverDir))["level-name"]?.ifBlank { null } ?: "world"

  fun write(serverDir: String, changes: Map<String, String>) {
    val f = file(serverDir)
    f.parentFile?.mkdirs()
    f.writeText(apply(read(serverDir), changes))
  }
}
