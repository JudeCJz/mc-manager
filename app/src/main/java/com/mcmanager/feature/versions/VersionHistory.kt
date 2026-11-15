package com.mcmanager.feature.versions

import com.mcmanager.core.data.db.ServerEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A server state we can return to: engine / version / build plus where its launch jar was saved. */
@Serializable
data class HistoryEntry(
  val id: String,
  val engine: String,
  val mcVersion: String,
  val build: String? = null,
  val launchSpec: String? = null,
  val javaMajor: Int = 21,
  /** Folder (relative to the server dir) holding the saved jar, or null (e.g. Forge, or jar missing). */
  val savedPath: String? = null,
  val savedFile: String? = null,
  val date: Long,
  /** Backup taken just before leaving this state, if any. */
  val backupId: Long? = null,
  val backupLabel: String? = null,
)

@Serializable
data class HistoryFile(val entries: List<HistoryEntry> = emptyList())

@Singleton
class VersionHistoryStore @Inject constructor(private val json: Json) {

  private fun file(dir: File) = File(dir, ".mcmanager/versions.json")

  fun read(dir: File): List<HistoryEntry> = try {
    val f = file(dir)
    if (f.exists()) json.decodeFromString<HistoryFile>(f.readText()).entries else emptyList()
  } catch (_: Throwable) {
    emptyList()
  }

  fun write(dir: File, entries: List<HistoryEntry>) {
    val f = file(dir)
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, "versions.json.tmp")
    tmp.writeText(json.encodeToString(HistoryFile.serializer(), HistoryFile(entries)))
    if (f.exists()) f.delete()
    tmp.renameTo(f)
  }

  private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

  /**
   * Records the server's current state, copying its launch jar to
   * `.mcmanager/versions/<engine>-<mc>-<build>/`. Forge (`@args` launch spec) only records the spec.
   */
  fun snapshot(server: ServerEntity, backupId: Long?, backupLabel: String?): HistoryEntry {
    val dir = File(server.dir)
    var savedPath: String? = null
    var savedFile: String? = null
    val spec = server.launchSpec
    if (spec != null && !spec.startsWith("@")) {
      val src = File(dir, spec)
      if (src.isFile) {
        val rel = ".mcmanager/versions/" + safe("${server.engine.name}-${server.mcVersion}-${server.engineBuild ?: "latest"}")
        val dst = File(dir, rel)
        dst.mkdirs()
        src.copyTo(File(dst, src.name), overwrite = true)
        savedPath = rel
        savedFile = src.name
      }
    }
    return HistoryEntry(
      id = System.currentTimeMillis().toString(),
      engine = server.engine.name,
      mcVersion = server.mcVersion,
      build = server.engineBuild,
      launchSpec = spec,
      javaMajor = server.javaMajor,
      savedPath = savedPath,
      savedFile = savedFile,
      date = System.currentTimeMillis(),
      backupId = backupId,
      backupLabel = backupLabel,
    )
  }

  /** Copies the saved jar of [entry] back to its launch spec location. False if there is nothing to copy. */
  fun restoreJar(server: ServerEntity, entry: HistoryEntry): Boolean {
    val spec = entry.launchSpec
    if (spec == null || spec.startsWith("@")) return true // nothing on disk to restore
    val path = entry.savedPath ?: return false
    val file = entry.savedFile ?: return false
    val src = File(File(server.dir), "$path/$file")
    if (!src.isFile) return false
    val dst = File(File(server.dir), spec)
    dst.parentFile?.mkdirs()
    src.copyTo(dst, overwrite = true)
    return true
  }

  fun deleteSaved(server: ServerEntity, entry: HistoryEntry, others: List<HistoryEntry>) {
    val path = entry.savedPath ?: return
    if (others.any { it.savedPath == path }) return // shared folder still referenced
    File(File(server.dir), path).deleteRecursively()
  }
}
