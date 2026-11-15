package com.mcmanager.feature.plugins

import com.mcmanager.core.model.Engine

/** Which loader family a jar was written for. */
enum class JarKind { BUKKIT, PAPER_PLUGIN, FABRIC, FORGE, NEOFORGE }

/** A problem found while comparing a jar with the server. Mapped to localized text in the UI. */
sealed interface PluginIssue {
  /** Jar is built for [found] but this server loads [expectedFolder]. */
  data class WrongLoader(val found: Set<JarKind>, val expectedFolder: String) : PluginIssue

  /** Declared Minecraft range [range] does not include [mcVersion]. */
  data class McMismatch(val range: String, val mcVersion: String) : PluginIssue

  /** Bukkit `api-version` is newer than the server. */
  data class ApiTooNew(val api: String, val mcVersion: String) : PluginIssue

  data class MissingDeps(val names: List<String>) : PluginIssue
}

/** Pure helper functions: version parsing, range matching and compatibility checks. */
object Compat {

  // ---- version numbers -------------------------------------------------------------------

  /** "1.21.4" -> [1,21,4]; "1.21-pre1" -> [1,21]; null if no leading number. */
  fun parseVersion(v: String): List<Int>? {
    val m = Regex("^v?(\\d+(?:\\.\\d+)*)").find(v.trim()) ?: return null
    return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
  }

  fun compare(a: List<Int>, b: List<Int>): Int {
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
      val x = a.getOrElse(i) { 0 }
      val y = b.getOrElse(i) { 0 }
      if (x != y) return x.compareTo(y)
    }
    return 0
  }

  // ---- ranges ----------------------------------------------------------------------------

  /**
   * Does [mcVersion] satisfy any of [ranges]? Each range may be a Fabric/semver style
   * expression or a Maven interval. Unparseable input counts as a match (never warn on doubt).
   */
  fun matchesAny(ranges: List<String>, mcVersion: String): Boolean {
    if (ranges.isEmpty()) return true
    val v = parseVersion(mcVersion) ?: return true
    return ranges.any { matches(it, v) }
  }

  fun matches(range: String, mcVersion: String): Boolean {
    val v = parseVersion(mcVersion) ?: return true
    return matches(range, v)
  }

  private fun matches(range: String, v: List<Int>): Boolean {
    val r = range.trim()
    if (r.isEmpty() || r == "*") return true
    return if (r.startsWith("[") || r.startsWith("(")) matchesMaven(r, v) else matchesSemver(r, v)
  }

  /** Maven intervals: "[1.20.1,1.21)", "[1.20,)", "(,1.21]", "[1.20.1]", unions "[1.20,1.21),[1.21.2,)". */
  private fun matchesMaven(range: String, v: List<Int>): Boolean {
    val sets = Regex("[\\[(][^\\[\\]()]*[\\])]").findAll(range).map { it.value }.toList()
    if (sets.isEmpty()) return true
    return sets.any { s ->
      val lowInc = s.first() == '['
      val highInc = s.last() == ']'
      val body = s.substring(1, s.length - 1)
      if (!body.contains(',')) {
        val e = parseVersion(body) ?: return@any true
        return@any compare(v, e) == 0
      }
      val (lo, hi) = body.split(',', limit = 2).map { it.trim() }
      var ok = true
      if (lo.isNotEmpty()) {
        val l = parseVersion(lo)
        if (l != null) {
          val c = compare(v, l)
          ok = ok && (c > 0 || (c == 0 && lowInc))
        }
      }
      if (hi.isNotEmpty()) {
        val h = parseVersion(hi)
        if (h != null) {
          val c = compare(v, h)
          ok = ok && (c < 0 || (c == 0 && highInc))
        }
      }
      ok
    }
  }

  /** Fabric / npm style: ">=1.21 <1.22", "~1.21.4", "^1.20", "1.21.x", "1.21", ">=1.20 || 1.19.x". */
  private fun matchesSemver(range: String, v: List<Int>): Boolean =
    range.split("||").any { alt -> alt.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { cmp(it, v) } }

  private fun cmp(token: String, v: List<Int>): Boolean {
    val t = token.trim()
    if (t == "*" || t.isEmpty()) return true
    val op = Regex("^(>=|<=|>|<|=|~|\\^)?(.*)$").find(t)!!
    val o = op.groupValues[1]
    val rest = op.groupValues[2]
    // wildcard: 1.21.x / 1.x / 1.21.*
    if (rest.contains('x', true) || rest.contains('*')) {
      val fixed = rest.split('.').takeWhile { it.toIntOrNull() != null }.map { it.toInt() }
      if (fixed.isEmpty()) return true
      return prefixEquals(v, fixed)
    }
    val bound = parseVersion(rest) ?: return true
    return when (o) {
      ">=" -> compare(v, bound) >= 0
      ">" -> compare(v, bound) > 0
      "<=" -> compare(v, bound) <= 0
      "<" -> compare(v, bound) < 0
      "~" -> compare(v, bound) >= 0 && compare(v, bumpTilde(bound)) < 0
      "^" -> compare(v, bound) >= 0 && compare(v, listOf(bound.first() + 1)) < 0
      else -> compare(v, bound) == 0 // exact ("1.21" == "1.21.0")
    }
  }

  private fun prefixEquals(v: List<Int>, prefix: List<Int>) =
    prefix.indices.all { v.getOrElse(it) { 0 } == prefix[it] }

  /** "~1.21.4" and "~1.21" both stay inside 1.21.*; "~1" stays inside 1.*. */
  private fun bumpTilde(b: List<Int>): List<Int> =
    if (b.size == 1) listOf(b[0] + 1) else listOf(b[0], b[1] + 1)

  /** Bukkit `api-version` (e.g. "1.21") must not be newer than the server's major.minor. */
  fun apiVersionOk(api: String, mcVersion: String): Boolean {
    val a = parseVersion(api) ?: return true
    val s = parseVersion(mcVersion) ?: return true
    return compare(a.take(2), s.take(2)) <= 0
  }

  // ---- loaders ---------------------------------------------------------------------------

  /** Mod/plugin folder name for an engine, or null if the engine takes neither (Vanilla). */
  fun folderFor(engine: Engine): String? = when (engine) {
    Engine.PAPER, Engine.PURPUR, Engine.SPIGOT -> "plugins"
    Engine.FABRIC, Engine.FORGE -> "mods"
    Engine.VANILLA -> null
  }

  /** Kinds of jar this engine can load. */
  fun acceptedKinds(engine: Engine): Set<JarKind> = when (engine) {
    Engine.PAPER, Engine.PURPUR, Engine.SPIGOT -> setOf(JarKind.BUKKIT, JarKind.PAPER_PLUGIN)
    Engine.FABRIC -> setOf(JarKind.FABRIC)
    Engine.FORGE -> setOf(JarKind.FORGE, JarKind.NEOFORGE)
    Engine.VANILLA -> emptySet()
  }

  /**
   * Compatibility of one jar with the server. [kinds] come from the jar; [mcRanges] are the
   * declared Minecraft ranges; [apiVersion] the Bukkit api-version; [hardDeps]/[present] are names
   * (compared case-insensitively) of required dependencies and of everything installed.
   */
  fun check(
    engine: Engine,
    mcVersion: String,
    kinds: Set<JarKind>,
    mcRanges: List<String>,
    apiVersion: String?,
    hardDeps: List<String>,
    present: Set<String>,
  ): List<PluginIssue> {
    val out = ArrayList<PluginIssue>()
    val folder = folderFor(engine) ?: return out
    if (kinds.isNotEmpty() && kinds.intersect(acceptedKinds(engine)).isEmpty()) {
      out += PluginIssue.WrongLoader(kinds, folder)
      return out // other checks are meaningless for the wrong loader
    }
    if (mcRanges.isNotEmpty() && !matchesAny(mcRanges, mcVersion)) {
      out += PluginIssue.McMismatch(mcRanges.joinToString(" || "), mcVersion)
    }
    if (apiVersion != null && !apiVersionOk(apiVersion, mcVersion)) {
      out += PluginIssue.ApiTooNew(apiVersion, mcVersion)
    }
    val have = present.map { it.lowercase() }.toSet()
    val missing = hardDeps.filter { it.lowercase() !in have }
    if (missing.isNotEmpty()) out += PluginIssue.MissingDeps(missing)
    return out
  }

  /** Modrinth/Hangar loader names to ask for, for an engine. */
  fun modrinthLoaders(engine: Engine): List<String> = when (engine) {
    Engine.PAPER -> listOf("paper", "purpur", "spigot", "bukkit")
    Engine.PURPUR -> listOf("purpur", "paper", "spigot", "bukkit")
    Engine.SPIGOT -> listOf("spigot", "bukkit")
    Engine.FABRIC -> listOf("fabric")
    Engine.FORGE -> listOf("forge")
    Engine.VANILLA -> emptyList()
  }

  fun modrinthProjectType(engine: Engine): String = if (engine.isModLoader) "mod" else "plugin"
}
