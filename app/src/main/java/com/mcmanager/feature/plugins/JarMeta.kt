package com.mcmanager.feature.plugins

import com.mcmanager.core.model.Engine
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Metadata read from inside a plugin / mod jar, flattened for the engine it is checked against. */
data class JarMeta(
  /** Every loader descriptor found in the jar. Empty = no recognised metadata. */
  val kinds: Set<JarKind>,
  val name: String?,
  val version: String?,
  /** Names / ids this jar provides (for dependency lookups). */
  val ids: Set<String>,
  /** Declared Minecraft version ranges (any may match). */
  val mcRanges: List<String>,
  val apiVersion: String?,
  val hardDeps: List<String>,
  val softDeps: List<String>,
) {
  companion object {
    val EMPTY = JarMeta(emptySet(), null, null, emptySet(), emptyList(), null, emptyList(), emptyList())
  }
}

private data class Partial(
  val name: String?,
  val version: String?,
  val ids: Set<String>,
  val mcRanges: List<String>,
  val apiVersion: String? = null,
  val hard: List<String> = emptyList(),
  val soft: List<String> = emptyList(),
)

object JarMetaReader {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private const val MAX_ENTRY = 2 * 1024 * 1024

  /** Never throws: a broken jar yields [JarMeta.EMPTY]. */
  fun read(file: File, engine: Engine): JarMeta = try {
    ZipFile(file).use { zip ->
      fun text(name: String): String? {
        val e = zip.getEntry(name) ?: return null
        if (e.size > MAX_ENTRY) return null
        return zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
      }
      val manifestVersion = text("META-INF/MANIFEST.MF")?.lineSequence()
        ?.firstOrNull { it.startsWith("Implementation-Version:") }?.substringAfter(':')?.trim()

      val found = LinkedHashMap<JarKind, Partial>()
      text("plugin.yml")?.let { found[JarKind.BUKKIT] = bukkit(it, paper = false) }
      text("paper-plugin.yml")?.let { found[JarKind.PAPER_PLUGIN] = bukkit(it, paper = true) }
      text("fabric.mod.json")?.let { runCatching { fabric(it) }.getOrNull()?.let { p -> found[JarKind.FABRIC] = p } }
      text("META-INF/neoforge.mods.toml")?.let { found[JarKind.NEOFORGE] = forge(it, manifestVersion) }
      text("META-INF/mods.toml")?.let { found[JarKind.FORGE] = forge(it, manifestVersion) }

      if (found.isEmpty()) return JarMeta.EMPTY
      // Prefer the descriptor the server can actually use; paper-plugin.yml wins over plugin.yml on Paper.
      val order = when (engine) {
        Engine.PAPER, Engine.PURPUR -> listOf(JarKind.PAPER_PLUGIN, JarKind.BUKKIT)
        Engine.FABRIC -> listOf(JarKind.FABRIC)
        Engine.FORGE -> listOf(JarKind.FORGE, JarKind.NEOFORGE)
        else -> listOf(JarKind.BUKKIT)
      }
      val key = order.firstOrNull { it in found } ?: found.keys.first()
      val p = found.getValue(key)
      JarMeta(
        kinds = found.keys,
        name = p.name,
        version = p.version,
        ids = found.values.flatMap { it.ids }.toSet(),
        mcRanges = p.mcRanges,
        apiVersion = p.apiVersion,
        hardDeps = p.hard,
        softDeps = p.soft,
      )
    }
  } catch (_: Throwable) {
    JarMeta.EMPTY
  }

  // ---- Bukkit / Paper ------------------------------------------------------------------

  private fun bukkit(text: String, paper: Boolean): Partial {
    val y = MiniYaml.parse(text)
    val name = MiniYaml.str(y["name"])
    var hard = MiniYaml.strings(y["depend"])
    var soft = MiniYaml.strings(y["softdepend"])
    if (paper) {
      val server = ((y["dependencies"] as? Map<*, *>)?.get("server")) as? Map<*, *>
      if (server != null) {
        hard = server.entries.filter { (it.value as? Map<*, *>)?.get("required")?.toString() != "false" }
          .map { it.key.toString() }
        soft = server.entries.filter { (it.value as? Map<*, *>)?.get("required")?.toString() == "false" }
          .map { it.key.toString() }
      }
    }
    return Partial(
      name = name,
      version = MiniYaml.str(y["version"]),
      ids = setOfNotNull(name),
      mcRanges = emptyList(),
      apiVersion = MiniYaml.str(y["api-version"]),
      hard = hard,
      soft = soft,
    )
  }

  // ---- Fabric --------------------------------------------------------------------------

  private val FABRIC_BUILTIN = setOf("minecraft", "fabricloader", "fabric-loader", "java", "fabric")

  private fun fabric(text: String): Partial {
    val o = json.parseToJsonElement(text).jsonObject
    val id = (o["id"] as? JsonPrimitive)?.contentOrNull
    val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: id
    val version = (o["version"] as? JsonPrimitive)?.contentOrNull
    val provides = (o["provides"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val depends = o["depends"] as? JsonObject
    val mc = depends?.get("minecraft")?.let { ranges(it) } ?: emptyList()
    val hard = depends?.keys?.filter { it !in FABRIC_BUILTIN } ?: emptyList()
    val soft = (o["recommends"] as? JsonObject)?.keys?.toList() ?: emptyList()
    return Partial(name, version, setOfNotNull(id) + provides, mc, null, hard, soft)
  }

  private fun ranges(e: JsonElement): List<String> = when (e) {
    is JsonPrimitive -> listOfNotNull(e.contentOrNull)
    is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
  }

  // ---- Forge / NeoForge ----------------------------------------------------------------

  private val FORGE_BUILTIN = setOf("minecraft", "forge", "neoforge", "java", "fml")

  private fun forge(text: String, manifestVersion: String?): Partial {
    val doc = MiniToml.parse(text)
    val mods = doc.tables.filter { it.name == "mods" }
    val ids = mods.mapNotNull { it.str("modId") }.toSet()
    val first = mods.firstOrNull()
    var version = first?.str("version")
    if (version != null && version.startsWith("\${")) version = manifestVersion
    val deps = doc.tables.filter { it.name.startsWith("dependencies.") }
    val mc = deps.filter { it.str("modId") == "minecraft" }.mapNotNull { it.str("versionRange") }
    val hard = deps.filter {
      val mandatory = it.str("mandatory")
      val type = it.str("type")
      (mandatory == "true" || type == "required")
    }.mapNotNull { it.str("modId") }.filter { it !in FORGE_BUILTIN }.distinct()
    return Partial(first?.str("displayName") ?: ids.firstOrNull(), version, ids, mc, null, hard, emptyList())
  }
}

/** Tiny YAML reader: nested maps, block lists, inline `[a, b]` lists, quoted scalars. Enough for plugin.yml. */
internal object MiniYaml {
  private class Line(val indent: Int, val text: String)

  fun parse(text: String): Map<String, Any?> {
    val ls = text.lineSequence()
      .map { stripComment(it.trimEnd('\r')) }
      .filter { it.isNotBlank() && it.trim() != "---" }
      .map { Line(it.length - it.trimStart().length, it.trim()) }
      .toList()
    if (ls.isEmpty()) return emptyMap()
    return parseMap(ls, intArrayOf(0), ls[0].indent)
  }

  private fun parseMap(ls: List<Line>, pos: IntArray, indent: Int): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    while (pos[0] < ls.size) {
      val l = ls[pos[0]]
      if (l.indent < indent) break
      if (l.indent > indent || l.text.startsWith("- ")) {
        if (l.text.startsWith("- ") && l.indent == indent) break
        pos[0]++
        continue
      }
      val c = colon(l.text)
      if (c < 0) { pos[0]++; continue }
      val key = unquote(l.text.substring(0, c).trim())
      val rest = l.text.substring(c + 1).trim()
      pos[0]++
      if (rest.isEmpty()) {
        val next = ls.getOrNull(pos[0])
        m[key] = when {
          next == null -> null
          next.text.startsWith("- ") && next.indent >= indent -> parseList(ls, pos, next.indent)
          next.indent > indent -> parseMap(ls, pos, next.indent)
          else -> null
        }
      } else {
        m[key] = inline(rest)
      }
    }
    return m
  }

  private fun parseList(ls: List<Line>, pos: IntArray, indent: Int): List<String> {
    val out = ArrayList<String>()
    while (pos[0] < ls.size) {
      val l = ls[pos[0]]
      if (l.indent != indent || !l.text.startsWith("- ")) break
      out += unquote(l.text.removePrefix("- ").trim())
      pos[0]++
    }
    return out
  }

  private fun inline(v: String): Any {
    if (v.startsWith("[") && v.endsWith("]")) {
      return v.substring(1, v.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }
    return unquote(v)
  }

  private fun colon(s: String): Int {
    var q = 0.toChar()
    for ((i, ch) in s.withIndex()) {
      if (q != 0.toChar()) { if (ch == q) q = 0.toChar(); continue }
      if (ch == '"' || ch == '\'') q = ch
      if (ch == ':' && (i + 1 == s.length || s[i + 1] == ' ')) return i
    }
    return -1
  }

  private fun stripComment(s: String): String {
    var q = 0.toChar()
    for ((i, ch) in s.withIndex()) {
      if (q != 0.toChar()) { if (ch == q) q = 0.toChar(); continue }
      if (ch == '"' || ch == '\'') q = ch
      if (ch == '#' && (i == 0 || s[i - 1] == ' ')) return s.substring(0, i)
    }
    return s
  }

  private fun unquote(s: String): String {
    val t = s.trim()
    return if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
      t.substring(1, t.length - 1)
    } else {
      t
    }
  }

  fun str(v: Any?): String? = when (v) {
    null -> null
    is String -> v.ifBlank { null }
    is Map<*, *>, is List<*> -> null
    else -> v.toString()
  }

  fun strings(v: Any?): List<String> = when (v) {
    is List<*> -> v.map { it.toString() }
    is String -> if (v.isBlank()) emptyList() else listOf(v)
    else -> emptyList()
  }
}

/** Tiny TOML reader for mods.toml: tables / arrays of tables with simple key = value pairs. */
internal object MiniToml {
  class Table(val name: String) {
    val values = LinkedHashMap<String, String>()
    fun str(k: String): String? = values[k]
  }

  class Doc(val tables: List<Table>)

  fun parse(text: String): Doc {
    val tables = ArrayList<Table>()
    var cur = Table("")
    tables += cur
    for (raw in text.lineSequence()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      if (line.startsWith("[")) {
        val name = line.trim('[', ']', ' ')
        cur = Table(name)
        tables += cur
        continue
      }
      val eq = line.indexOf('=')
      if (eq < 0) continue
      val key = line.substring(0, eq).trim().trim('"')
      var value = line.substring(eq + 1).trim()
      if (value.startsWith("'''") || value.startsWith("\"\"\"")) continue // multi-line text: not needed
      if (value.startsWith("\"")) {
        val end = value.indexOf('"', 1)
        value = if (end > 0) value.substring(1, end) else value.trim('"')
      } else if (value.startsWith("'")) {
        val end = value.indexOf('\'', 1)
        value = if (end > 0) value.substring(1, end) else value.trim('\'')
      } else {
        value = value.substringBefore('#').trim()
      }
      cur.values[key] = value
    }
    return Doc(tables)
  }
}
