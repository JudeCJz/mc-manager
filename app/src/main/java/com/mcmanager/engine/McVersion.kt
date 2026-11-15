package com.mcmanager.engine

/** Minecraft version helpers (supports both "1.21.4" and the newer "26.1" style). */
object McVersion {
  private val release = Regex("^\\d+(\\.\\d+)*$")
  private val tokens = Regex("\\d+|[A-Za-z]+")

  fun isRelease(v: String): Boolean = release.matches(v)

  fun compare(a: String, b: String): Int {
    val ta = tokens.findAll(a).map { it.value }.toList()
    val tb = tokens.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ta.size, tb.size)) {
      val x = ta[i]
      val y = tb[i]
      val nx = x.toIntOrNull()
      val ny = y.toIntOrNull()
      val c = when {
        nx != null && ny != null -> nx.compareTo(ny)
        nx != null -> 1
        ny != null -> -1
        else -> x.compareTo(y)
      }
      if (c != 0) return c
    }
    return ta.size.compareTo(tb.size)
  }

  val newestFirst: Comparator<String> = Comparator { a, b -> compare(b, a) }

  /** Java major version for a Minecraft version when Mojang's metadata is not available. */
  fun fallbackJava(mc: String): Int = when {
    compare(mc, "26") >= 0 -> 25
    compare(mc, "1.20.5") >= 0 -> 21
    compare(mc, "1.17") >= 0 -> 17
    else -> 8
  }
}
