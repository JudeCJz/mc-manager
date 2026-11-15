package com.mcmanager.engine.providers

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.Engine
import com.mcmanager.engine.CachedFetcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Mojang's launcher metadata (version manifest + per-version JSON). */
@Singleton
class MojangApi @Inject constructor(private val fetcher: CachedFetcher) {
  data class Entry(val id: String, val type: String, val url: String)

  private suspend fun manifest(): List<Entry> =
    fetcher.json(MANIFEST).obj()?.get("versions").arr().orEmpty().mapNotNull { e ->
      val o = e.obj() ?: return@mapNotNull null
      Entry(o.str("id") ?: return@mapNotNull null, o.str("type").orEmpty(), o.str("url") ?: return@mapNotNull null)
    }

  /** Versions that have a server jar (1.2.5 and newer), newest first. */
  suspend fun serverVersions(includeSnapshots: Boolean): List<String> {
    val all = manifest()
    val cut = all.indexOfFirst { it.id == "1.2.5" }.let { if (it < 0) all.size else it + 1 }
    return all.take(cut)
      .filter { it.type == "release" || (includeSnapshots && it.type == "snapshot") }
      .map { it.id }
  }

  suspend fun versionJson(id: String): JsonObject? {
    val entry = manifest().firstOrNull { it.id == id } ?: return null
    return fetcher.json(entry.url, CachedFetcher.TTL_LONG).obj()
  }

  /** Required Java major from Mojang's metadata; null if unknown. */
  suspend fun javaMajor(id: String): Int? =
    versionJson(id)?.get("javaVersion").obj()?.get("majorVersion").str()?.toIntOrNull()

  companion object {
    const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
  }
}

@Singleton
class VanillaProvider @Inject constructor(
  @ApplicationContext context: Context,
  http: OkHttpClient,
  fetcher: CachedFetcher,
  private val mojang: MojangApi,
) : BaseProvider(context, http, fetcher) {
  override val engine = Engine.VANILLA

  override suspend fun mcVersions(includeSnapshots: Boolean): List<String> = mojang.serverVersions(includeSnapshots)

  /** Vanilla has no builds. */
  override suspend fun latestBuild(mcVersion: String): String? = null

  override suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String {
    val dl = mojang.versionJson(server.mcVersion)?.get("downloads").obj()?.get("server").obj()
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Vanilla", server.mcVersion))
    val url = dl.str("url") ?: throw IOException("Vanilla download URL missing")
    downloadVerified(
      url, File(env.dir, "server.jar"), "SHA-1", dl.str("sha1"), env,
      context.getString(R.string.eng_dl_server, "Vanilla", server.mcVersion),
    )
    return "server.jar"
  }
}
