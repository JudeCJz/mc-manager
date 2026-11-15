package com.mcmanager.engine.providers

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.Engine
import com.mcmanager.engine.CachedFetcher
import com.mcmanager.engine.McVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class PaperProvider @Inject constructor(
  @ApplicationContext context: Context,
  http: OkHttpClient,
  fetcher: CachedFetcher,
) : BaseProvider(context, http, fetcher) {
  override val engine = Engine.PAPER
  private val api = "https://fill.papermc.io/v3/projects/paper"

  override suspend fun mcVersions(includeSnapshots: Boolean): List<String> {
    val groups = fetcher.json(api).obj()?.get("versions").obj() ?: return emptyList()
    return groups.values.flatMap { it.arr().orEmpty().mapNotNull { v -> v.str() } }
      .distinct()
      .filter { includeSnapshots || McVersion.isRelease(it) }
      .sortedWith(McVersion.newestFirst)
  }

  override suspend fun latestBuild(mcVersion: String): String? = notFoundAsNull {
    fetcher.json("$api/versions/$mcVersion/builds/latest").obj()?.get("id").str()
  }

  override suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String {
    val id = build ?: latestBuild(server.mcVersion)
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Paper", server.mcVersion))
    val info = fetcher.json("$api/versions/${server.mcVersion}/builds/$id").obj()
    val dl = info?.get("downloads").obj()?.get("server:default").obj()
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Paper", server.mcVersion))
    val url = dl.str("url") ?: throw IOException("Paper download URL missing")
    downloadVerified(
      url, File(env.dir, "server.jar"), "SHA-256", dl["checksums"].obj()?.str("sha256"), env,
      context.getString(R.string.eng_dl_server, "Paper", "${server.mcVersion} #$id"),
    )
    return "server.jar"
  }

  override suspend fun updateAvailable(server: ServerEntity): String? {
    val current = server.engineBuild?.toIntOrNull() ?: return null
    val latest = latestBuild(server.mcVersion)?.toIntOrNull() ?: return null
    return if (latest > current) "Build $latest" else null
  }
}
