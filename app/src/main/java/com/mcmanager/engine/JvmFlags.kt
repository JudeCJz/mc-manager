package com.mcmanager.engine

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of res/raw/jvm_presets.json. */
@Serializable
data class JvmPreset(
  val id: String,
  val name: String,
  val description: String = "",
  val flags: List<String> = emptyList(),
  val minJava: Int = 8,
)

/** Loads the presets from res/raw/jvm_presets.json (cached). Show [presets] in a dropdown. */
@Singleton
class JvmPresetRepository @Inject constructor(@ApplicationContext private val context: Context) {

  @Volatile private var cache: List<JvmPreset>? = null

  fun presets(): List<JvmPreset> = cache ?: load(context).also { cache = it }

  fun byId(id: String?): JvmPreset? = presets().firstOrNull { it.id == id }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<JvmPreset> =
      context.resources.openRawResource(R.raw.jvm_presets).bufferedReader().use {
        json.decodeFromString<List<JvmPreset>>(it.readText())
      }
  }
}

object JvmFlags {

  /** All presets (public so the UI can show a dropdown without Hilt). */
  fun presets(context: Context): List<JvmPreset> = JvmPresetRepository.load(context)

  private val gcSelectors = mapOf(
    "G1" to "-XX:+UseG1GC",
    "ZGC" to "-XX:+UseZGC",
    "SHENANDOAH" to "-XX:+UseShenandoahGC",
    "PARALLEL" to "-XX:+UseParallelGC",
    "SERIAL" to "-XX:+UseSerialGC",
  )
  private val gcFlagRegex = Regex("^-XX:\\+Use(G1|Z|Shenandoah|Parallel|Serial|ConcMarkSweep)GC$")

  /**
   * JVM arguments (without the java binary and without -jar): -Xms/-Xmx, preset flags, GC flag when the
   * preset does not choose one, user args, encoding and tmpdir.
   *
   * @param gcOverride one of G1/ZGC/Shenandoah/Parallel/Serial; when set it replaces the preset's own GC
   *   selection (and its G1-specific tuning). Null = preset wins, else `server.gc`.
   */
  fun build(server: ServerEntity, preset: JvmPreset?, gcOverride: String? = null): List<String> {
    val out = ArrayList<String>()
    val xmx = server.xmxMb.coerceAtLeast(256)
    val xms = server.xmsMb.coerceIn(128, xmx)
    out += "-Xms${xms}M"
    out += "-Xmx${xmx}M"

    var flags = preset?.flags.orEmpty()
    if (server.javaMajor >= 23) flags = flags.filter { it != "-XX:+ZGenerational" }
    if (gcOverride != null) flags = flags.filter { !gcFlagRegex.matches(it) && !it.contains("G1") }
    out += flags

    if (out.none { gcFlagRegex.matches(it) }) {
      val gc = (gcOverride ?: server.gc).uppercase()
      gcSelectors[gc]?.let { out += it }
      if (gc == "ZGC" && server.javaMajor in 21..22 && "-XX:+ZGenerational" !in out) out += "-XX:+ZGenerational"
    }

    out += splitArgs(server.extraJvmArgs)
    out += "-Dfile.encoding=UTF-8"
    out += "-Djava.io.tmpdir=${server.dir.trimEnd('/', '\\')}/tmp"
    out += "-DPaper.IgnoreJavaVersion=true"
    return out
  }

  /** Whitespace split that keeps "double" and 'single' quoted sections together (quotes are removed). */
  fun splitArgs(s: String): List<String> {
    val args = ArrayList<String>()
    val cur = StringBuilder()
    var quote = 0.toChar()
    var inToken = false
    for (c in s) {
      when {
        quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else cur.append(c)
        c == '"' || c == '\'' -> {
          quote = c
          inToken = true
        }
        c.isWhitespace() -> if (inToken) {
          args += cur.toString()
          cur.setLength(0)
          inToken = false
        }
        else -> {
          cur.append(c)
          inToken = true
        }
      }
    }
    if (inToken) args += cur.toString()
    return args
  }
}
