package com.mcmanager.feature.plugins

import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.net.download
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

enum class Source { MODRINTH, HANGAR }

/** What we remember about a downloaded file (sidecar `.mcmanager-index.json`, keyed by the enabled file name). */
@Serializable
data class IndexEntry(
  val source: String,
  val projectId: String,
  val title: String = "",
  val versionId: String,
  val versionNumber: String = "",
  val sha1: String = "",
)

@Serializable
data class PluginIndex(val files: Map<String, IndexEntry> = emptyMap())

data class InstalledItem(
  /** Name on disk (may end in `.disabled`). */
  val fileName: String,
  /** Name with `.disabled` removed; also the index key. */
  val baseName: String,
  val enabled: Boolean,
  val sizeBytes: Long,
  val meta: JarMeta,
  val issues: List<PluginIssue>,
  val index: IndexEntry?,
) {
  val displayName: String get() = meta.name ?: index?.title?.ifBlank { null } ?: baseName.removeSuffix(".jar")
  val displayVersion: String? get() = meta.version ?: index?.versionNumber?.ifBlank { null }
}

/** One downloadable version of a project, unified over Modrinth and Hangar. */
data class VersionOption(
  val source: Source,
  val projectId: String,
  val projectTitle: String,
  val versionId: String,
  val name: String,
  val number: String,
  /** "release", "beta", "alpha" (Hangar channels are mapped by name). */
  val channel: String,
  val published: String,
  val fileName: String?,
  val url: String?,
  /** Set for Hangar versions hosted elsewhere: open in a browser instead of downloading. */
  val externalUrl: String?,
  val size: Long,
  val sha1: String?,
  val sha512: String?,
  val sha256: String?,
  val deps: List<MrDependency> = emptyList(),
  /** Required plugin dependencies reported by Hangar (names only; not auto-installed). */
  val hangarRequires: List<String> = emptyList(),
) {
  val downloadable: Boolean get() = url != null && fileName != null
}

data class InstallPlan(
  val main: VersionOption,
  val dependencies: List<VersionOption>,
  /** Required dependencies we could not find a compatible version for. */
  val unresolved: List<String>,
) {
  val all: List<VersionOption> get() = listOf(main) + dependencies
}

data class StepProgress(val index: Int, val total: Int, val name: String, val fraction: Float?)

@Singleton
class PluginRepository @Inject constructor(
  private val modrinth: ModrinthApi,
  private val hangar: HangarApi,
  private val client: OkHttpClient,
  private val json: Json,
) {
  private data class CacheKey(val path: String, val size: Long, val modified: Long, val engine: String)

  private val metaCache = HashMap<CacheKey, JarMeta>()

  fun folder(server: ServerEntity): File? = Compat.folderFor(server.engine)?.let { File(server.dir, it) }

  // ---- index ---------------------------------------------------------------------------

  private fun indexFile(folder: File) = File(folder, ".mcmanager-index.json")

  fun readIndex(folder: File): PluginIndex = try {
    val f = indexFile(folder)
    if (f.exists()) json.decodeFromString(f.readText()) else PluginIndex()
  } catch (_: Throwable) {
    PluginIndex()
  }

  @Synchronized
  private fun writeIndex(folder: File, index: PluginIndex) {
    folder.mkdirs()
    val tmp = File(folder, ".mcmanager-index.json.tmp")
    tmp.writeText(json.encodeToString(PluginIndex.serializer(), index))
    val dest = indexFile(folder)
    if (dest.exists()) dest.delete()
    tmp.renameTo(dest)
  }

  @Synchronized
  private fun updateIndex(folder: File, transform: (Map<String, IndexEntry>) -> Map<String, IndexEntry>) {
    writeIndex(folder, PluginIndex(transform(readIndex(folder).files)))
  }

  // ---- installed -----------------------------------------------------------------------

  suspend fun list(server: ServerEntity): List<InstalledItem> = withContext(Dispatchers.IO) {
    val dir = folder(server) ?: return@withContext emptyList()
    val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled")) }
      ?.sortedBy { it.name.lowercase() } ?: return@withContext emptyList()
    val index = readIndex(dir)
    val metas = files.map { f ->
      val key = CacheKey(f.absolutePath, f.length(), f.lastModified(), server.engine.name)
      f to metaCache.getOrPut(key) { JarMetaReader.read(f, server.engine) }
    }
    // Everything that is loaded counts as "present" for dependency checks.
    val present = metas.filter { !it.first.name.endsWith(".disabled") }
      .flatMap { it.second.ids }.toSet()
    metas.map { (f, meta) ->
      val enabled = !f.name.endsWith(".disabled")
      val base = f.name.removeSuffix(".disabled")
      InstalledItem(
        fileName = f.name,
        baseName = base,
        enabled = enabled,
        sizeBytes = f.length(),
        meta = meta,
        issues = Compat.check(
          server.engine, server.mcVersion, meta.kinds, meta.mcRanges, meta.apiVersion,
          meta.hardDeps, present,
        ),
        index = index.files[base],
      )
    }
  }

  suspend fun setEnabled(server: ServerEntity, item: InstalledItem, enabled: Boolean) = withContext(Dispatchers.IO) {
    val dir = folder(server) ?: return@withContext
    val from = File(dir, item.fileName)
    val to = File(dir, if (enabled) item.baseName else item.baseName + ".disabled")
    if (from != to) {
      if (to.exists()) throw IOException("${to.name} already exists")
      if (!from.renameTo(to)) throw IOException("Could not rename ${from.name}")
    }
  }

  suspend fun delete(server: ServerEntity, item: InstalledItem) = withContext(Dispatchers.IO) {
    val dir = folder(server) ?: return@withContext
    File(dir, item.fileName).delete()
    updateIndex(dir) { it - item.baseName }
  }

  // ---- browsing: versions ----------------------------------------------------------------

  private fun hangarPlatform() = "PAPER"

  private fun channelOf(mr: String) = when (mr) {
    "release", "beta", "alpha" -> mr
    else -> "release"
  }

  fun modrinthOption(v: MrVersion, title: String): VersionOption {
    val f = v.jarFile()
    return VersionOption(
      source = Source.MODRINTH,
      projectId = v.projectId,
      projectTitle = title,
      versionId = v.id,
      name = v.name.ifBlank { v.versionNumber },
      number = v.versionNumber,
      channel = channelOf(v.versionType),
      published = v.datePublished.take(10),
      fileName = f?.filename?.let { File(it).name },
      url = f?.url,
      externalUrl = null,
      size = f?.size ?: 0,
      sha1 = f?.hashes?.sha1,
      sha512 = f?.hashes?.sha512,
      sha256 = null,
      deps = v.dependencies,
    )
  }

  private fun hangarOption(slug: String, title: String, v: HgVersion): VersionOption {
    val d = v.downloads[hangarPlatform()]
    val ch = v.channel.name.lowercase()
    val channel = when {
      ch.contains("release") -> "release"
      ch.contains("alpha") -> "alpha"
      ch.isBlank() -> "release"
      else -> "beta"
    }
    val url = d?.downloadUrl
    return VersionOption(
      source = Source.HANGAR,
      projectId = slug,
      projectTitle = title,
      versionId = v.id.toString(),
      name = v.name,
      number = v.name,
      channel = channel,
      published = v.createdAt.take(10),
      fileName = (d?.fileInfo?.name ?: url?.substringAfterLast('/')?.substringBefore('?'))?.let { File(it).name },
      url = url,
      externalUrl = d?.externalUrl,
      size = d?.fileInfo?.sizeBytes ?: 0,
      sha1 = null,
      sha512 = null,
      sha256 = d?.fileInfo?.sha256Hash,
      hangarRequires = v.pluginDependencies[hangarPlatform()].orEmpty().filter { it.required }.map { it.name },
    )
  }

  /** Versions of a project that work with this server, newest first. */
  suspend fun versionsFor(server: ServerEntity, source: Source, projectId: String, title: String): List<VersionOption> =
    when (source) {
      Source.MODRINTH ->
        modrinth.projectVersions(projectId, Compat.modrinthLoaders(server.engine), server.mcVersion)
          .map { modrinthOption(it, title) }
      Source.HANGAR ->
        hangar.versions(projectId, hangarPlatform(), server.mcVersion).map { hangarOption(projectId, title, it) }
    }

  /** Newest release, else newest beta/alpha. */
  fun best(options: List<VersionOption>): VersionOption? {
    val usable = options.filter { it.downloadable || it.externalUrl != null }
    return usable.firstOrNull { it.channel == "release" }
      ?: usable.firstOrNull { it.channel == "beta" }
      ?: usable.firstOrNull()
  }

  // ---- install -------------------------------------------------------------------------

  /** Works out what installing [main] pulls in (required Modrinth dependencies, recursively). */
  suspend fun plan(server: ServerEntity, main: VersionOption, installed: List<InstalledItem>): InstallPlan {
    if (main.source != Source.MODRINTH) return InstallPlan(main, emptyList(), emptyList())
    val dir = folder(server)
    val index = dir?.let { readIndex(it) } ?: PluginIndex()
    val seen = HashSet<String>()
    seen += index.files.values.filter { it.source == Source.MODRINTH.name }.map { it.projectId }
    seen += main.projectId
    val present = installed.filter { it.enabled }.flatMap { it.meta.ids }.map { it.lowercase() }.toSet()
    val out = ArrayList<VersionOption>()
    val unresolved = ArrayList<String>()
    resolveDeps(main.deps, Compat.modrinthLoaders(server.engine), server.mcVersion, seen, present, out, unresolved, 0)
    return InstallPlan(main, out, unresolved)
  }

  private suspend fun resolveDeps(
    deps: List<MrDependency>,
    loaders: List<String>,
    mc: String,
    seen: MutableSet<String>,
    present: Set<String>,
    out: MutableList<VersionOption>,
    unresolved: MutableList<String>,
    depth: Int,
  ) {
    if (depth > 8) return
    for (d in deps) {
      if (d.type != "required") continue
      val pinned = d.versionId?.let { runCatching { modrinth.version(it) }.getOrNull() }
      val pid = d.projectId ?: pinned?.projectId ?: continue
      if (!seen.add(pid)) continue
      val proj = runCatching { modrinth.project(pid) }.getOrNull()
      val title = proj?.title ?: pid
      if (proj != null && proj.slug.lowercase() in present) continue
      val v = pinned ?: runCatching { modrinth.projectVersions(pid, loaders, mc) }.getOrNull()
        ?.let { list ->
          list.firstOrNull { it.versionType == "release" && it.jarFile() != null }
            ?: list.firstOrNull { it.jarFile() != null }
        }
      if (v == null || v.jarFile() == null) {
        unresolved += title
        continue
      }
      out += modrinthOption(v, title)
      resolveDeps(v.dependencies, loaders, mc, seen, present, out, unresolved, depth + 1)
    }
  }

  /** Downloads and verifies every item of [plan], writes index entries. Throws on the first failure. */
  suspend fun install(server: ServerEntity, plan: InstallPlan, onProgress: (StepProgress) -> Unit) {
    val dir = folder(server) ?: throw IOException("This engine has no plugin folder")
    val items = plan.all
    items.forEachIndexed { i, opt ->
      val name = opt.fileName ?: throw IOException("${opt.projectTitle}: no downloadable file")
      val url = opt.url ?: throw IOException("${opt.projectTitle}: hosted elsewhere")
      onProgress(StepProgress(i, items.size, opt.projectTitle, 0f))
      val dest = File(dir, name)
      client.download(url, dest) { f -> onProgress(StepProgress(i, items.size, opt.projectTitle, f)) }
      withContext(Dispatchers.IO) {
        verify(dest, opt)
        val sha1 = hash(dest, "SHA-1")
        replaceOld(dir, opt, keep = name)
        File(dir, "$name.disabled").delete()
        updateIndex(dir) {
          it + (name to IndexEntry(opt.source.name, opt.projectId, opt.projectTitle, opt.versionId, opt.number, sha1))
        }
      }
    }
  }

  /** Removes older files that came from the same project so a reinstall does not leave duplicates. */
  private fun replaceOld(dir: File, opt: VersionOption, keep: String) {
    val idx = readIndex(dir)
    val old = idx.files.filter { (n, e) -> n != keep && e.source == opt.source.name && e.projectId == opt.projectId }
    for (n in old.keys) {
      File(dir, n).delete()
      File(dir, "$n.disabled").delete()
    }
    if (old.isNotEmpty()) updateIndex(dir) { it - old.keys }
  }

  private fun verify(file: File, opt: VersionOption) {
    val ok = when {
      opt.sha512 != null -> hash(file, "SHA-512").equals(opt.sha512, true)
      opt.sha1 != null -> hash(file, "SHA-1").equals(opt.sha1, true)
      opt.sha256 != null -> hash(file, "SHA-256").equals(opt.sha256, true)
      else -> true
    }
    if (!ok) {
      file.delete()
      throw IOException("Checksum mismatch for ${file.name}")
    }
  }

  private fun hash(file: File, alg: String): String {
    val md = MessageDigest.getInstance(alg)
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  // ---- updates -------------------------------------------------------------------------

  /**
   * Asks Modrinth about every installed jar. Also records files it recognises that the index did not
   * know yet. Returns baseName -> newer version.
   */
  suspend fun checkUpdates(server: ServerEntity, items: List<InstalledItem>): Map<String, MrVersion> {
    val dir = folder(server) ?: return emptyMap()
    val hashes = withContext(Dispatchers.IO) { items.associateWith { hash(File(dir, it.fileName), "SHA-1") } }
    val sha = hashes.values.toList()
    val loaders = Compat.modrinthLoaders(server.engine)
    val known = modrinth.versionsByHashes(sha)
    val latest = modrinth.latestForHashes(sha, loaders, server.mcVersion)

    withContext(Dispatchers.IO) {
      val add = HashMap<String, IndexEntry>()
      for ((item, h) in hashes) {
        val cur = known[h] ?: continue
        if (item.index == null || item.index.sha1 != h) {
          add[item.baseName] = IndexEntry(Source.MODRINTH.name, cur.projectId, item.displayName, cur.id, cur.versionNumber, h)
        }
      }
      if (add.isNotEmpty()) updateIndex(dir) { it + add }
    }

    val out = HashMap<String, MrVersion>()
    for ((item, h) in hashes) {
      val lat = latest[h] ?: continue
      val curId = known[h]?.id ?: item.index?.versionId
      val latFile = lat.jarFile() ?: continue
      if (lat.id != curId && !latFile.hashes.sha1.equals(h, true)) out[item.baseName] = lat
    }
    return out
  }

  /** Downloads [newer] for [item], verifies it, removes the old file and updates the index. */
  suspend fun update(server: ServerEntity, item: InstalledItem, newer: MrVersion, onProgress: (StepProgress) -> Unit) {
    val dir = folder(server) ?: throw IOException("This engine has no plugin folder")
    val title = item.index?.title?.ifBlank { null } ?: item.displayName
    val opt = modrinthOption(newer, title)
    val name = opt.fileName ?: throw IOException("No jar in the new version")
    val url = opt.url ?: throw IOException("No download URL")
    onProgress(StepProgress(0, 1, title, 0f))
    val dest = File(dir, name)
    // Keep the enabled/disabled state of the file being replaced.
    val target = if (item.enabled) dest else File(dir, "$name.disabled")
    val tmp = File(dir, "$name.new")
    client.download(url, tmp) { f -> onProgress(StepProgress(0, 1, title, f)) }
    withContext(Dispatchers.IO) {
      try {
        verify(tmp, opt)
        val sha1 = hash(tmp, "SHA-1")
        File(dir, item.fileName).delete()
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not move the new file into place")
        updateIndex(dir) {
          (it - item.baseName) + (name to IndexEntry(Source.MODRINTH.name, opt.projectId, title, opt.versionId, opt.number, sha1))
        }
      } finally {
        tmp.delete()
      }
    }
  }
}
