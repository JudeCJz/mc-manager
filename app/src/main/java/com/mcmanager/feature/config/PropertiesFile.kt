package com.mcmanager.feature.config

/**
 * A java .properties file that keeps comments, blank lines and line order. Values are replaced in place;
 * new keys are appended at the end. Untouched lines are written back unchanged.
 */
class PropertiesFile private constructor(private val lines: MutableList<String>) {

  private class Parsed(val key: String, val value: String)

  private fun parseLine(line: String): Parsed? {
    val t = line.trimStart()
    if (t.isEmpty() || t[0] == '#' || t[0] == '!') return null
    var i = 0
    val rawKey = StringBuilder()
    while (i < t.length) {
      val c = t[i]
      if (c == '\\' && i + 1 < t.length) {
        rawKey.append(c).append(t[i + 1])
        i += 2
        continue
      }
      if (c == '=' || c == ':' || c == ' ' || c == '\t') break
      rawKey.append(c)
      i++
    }
    while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
    if (i < t.length && (t[i] == '=' || t[i] == ':')) i++
    while (i < t.length && (t[i] == ' ' || t[i] == '\t')) i++
    return Parsed(unescape(rawKey.toString()), unescape(t.substring(i).trimEnd('\r')))
  }

  fun get(key: String): String? {
    var found: String? = null
    for (l in lines) parseLine(l)?.let { if (it.key == key) found = it.value }
    return found
  }

  fun has(key: String): Boolean = get(key) != null

  fun keys(): List<String> = lines.mapNotNull { parseLine(it)?.key }

  fun toMap(): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    for (l in lines) parseLine(l)?.let { m[it.key] = it.value }
    return m
  }

  /** Sets [key]; rewrites its line only if the value actually changed, otherwise appends a new line. */
  fun set(key: String, value: String) {
    var last = -1
    for (i in lines.indices) if (parseLine(lines[i])?.key == key) last = i
    if (last >= 0) {
      if (parseLine(lines[last])!!.value != value) lines[last] = escape(key, true) + "=" + escape(value, false)
    } else {
      lines.add(escape(key, true) + "=" + escape(value, false))
    }
  }

  fun render(): String = lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n"

  companion object {
    fun parse(text: String): PropertiesFile {
      val l = text.replace("\r\n", "\n").split("\n").toMutableList()
      if (l.isNotEmpty() && l.last().isEmpty()) l.removeAt(l.size - 1)
      return PropertiesFile(l)
    }

    fun empty() = PropertiesFile(mutableListOf())

    fun unescape(s: String): String {
      if (!s.contains('\\')) return s
      val sb = StringBuilder()
      var i = 0
      while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
          val n = s[i + 1]
          when (n) {
            'u' -> {
              val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
              val cp = hex.toIntOrNull(16)
              if (cp != null && hex.length == 4) {
                sb.append(cp.toChar())
                i += 6
                continue
              }
              sb.append('u')
            }
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'f' -> sb.append('\u000c')
            else -> sb.append(n)
          }
          i += 2
        } else {
          sb.append(c)
          i++
        }
      }
      return sb.toString()
    }

    fun escape(s: String, isKey: Boolean): String {
      val sb = StringBuilder()
      for ((idx, c) in s.withIndex()) {
        when {
          c == '\\' -> sb.append("\\\\")
          c == '\n' -> sb.append("\\n")
          c == '\r' -> sb.append("\\r")
          c == '\t' -> sb.append("\\t")
          c == ':' || c == '=' || c == '#' || c == '!' -> sb.append('\\').append(c)
          c == ' ' && (isKey || idx == 0) -> sb.append("\\ ")
          c.code < 0x20 || c.code > 0x7e -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
          else -> sb.append(c)
        }
      }
      return sb.toString()
    }
  }
}
