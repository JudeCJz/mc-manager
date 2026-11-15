package com.mcmanager.service

/** Events extracted from server console output. */
sealed interface LogEvent {
  data class PlayerJoined(val name: String) : LogEvent
  data class PlayerLeft(val name: String) : LogEvent
  data class PlayerList(val count: Int, val max: Int, val names: List<String>) : LogEvent
  data class Tps(val oneMinute: Double) : LogEvent
  data object Done : LogEvent
}

object LogParser {
  private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
  private val sectionCodes = Regex("§[0-9a-fk-orA-FK-OR]")
  private val joinLeft = Regex("^(\\S+) (joined|left) the game$")
  private val listRegex = Regex("There are (\\d+) of a max of (\\d+) players online:\\s*(.*)$")
  private val listOld = Regex("There are (\\d+)/(\\d+) players online:\\s*(.*)$")
  private val tpsRegex = Regex("TPS from last ([^:]+):\\s*(.*)$")
  private val doneRegex = Regex("Done \\(.*\\)! For help")
  private val errorWords = Regex(
    "(?i)(exception|\\berror\\b|fatal|unable to access jarfile|could not create the java virtual machine|outofmemory)",
  )
  private val stackLine = Regex("^\\s+at \\S+")

  fun stripAnsi(s: String): String = ansi.replace(s, "")

  /** Message part of a log line, after the "[time level]: " prefix (also Forge style). */
  fun message(line: String): String {
    val clean = sectionCodes.replace(stripAnsi(line), "").trimEnd()
    if (clean.startsWith("[")) {
      val i = clean.indexOf("]: ")
      if (i >= 0) return clean.substring(i + 3)
    }
    return clean
  }

  fun parse(line: String): List<LogEvent> {
    val msg = message(line)
    if (msg.isEmpty()) return emptyList()
    val out = ArrayList<LogEvent>(1)
    joinLeft.find(msg)?.let {
      val name = it.groupValues[1]
      out += if (it.groupValues[2] == "joined") LogEvent.PlayerJoined(name) else LogEvent.PlayerLeft(name)
    }
    (listRegex.find(msg) ?: listOld.find(msg))?.let {
      val names = it.groupValues[3].split(',').map { n -> n.trim() }.filter { n -> n.isNotEmpty() }
      out += LogEvent.PlayerList(it.groupValues[1].toInt(), it.groupValues[2].toInt(), names)
    }
    tpsRegex.find(msg)?.let { m ->
      val labels = m.groupValues[1].split(',').map { it.trim() }
      val values = m.groupValues[2].split(',').map { it.trim().trimStart('*').trim() }
      val idx = labels.indexOf("1m").takeIf { it >= 0 } ?: 0
      values.getOrNull(idx)?.let { v -> Regex("^\\d+(\\.\\d+)?").find(v)?.value?.toDoubleOrNull() }
        ?.let { out += LogEvent.Tps(it) }
    }
    if (doneRegex.containsMatchIn(msg)) out += LogEvent.Done
    return out
  }

  /** True for log lines worth showing in red / remembering as a crash reason. */
  fun isErrorLevel(line: String): Boolean =
    line.contains("ERROR]") || line.contains("/ERROR") || line.contains("FATAL]") ||
      stackLine.containsMatchIn(line) || line.startsWith("Caused by:") || line.startsWith("Exception in thread")

  /** True if a line is a plausible crash reason (as opposed to a stack frame). */
  fun isCrashReason(line: String): Boolean = !stackLine.containsMatchIn(line) && errorWords.containsMatchIn(line)
}
