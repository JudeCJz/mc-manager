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
class PurpurProvider @Inject constructor(
  @ApplicationContext context: Context,
  http: OkHttpClient,
  fetcher: CachedFetcher,
) : BaseProvider(context, http, fetcher) {
  override val engine = Engine.PURPUR
  private val api = "https://api.purpurmc.org/v2/purpur"

  override suspend fun mcVersions(includeSnapshots: Boolean): List<String> =
    fetcher.json(api).obj()?.get("versions").arr().orEmpty().mapNotNull { it.str() }
      .filter { includeSnapshots || McVersion.isRelease(it) }
      .sortedWith(McVersion.newestFirst)

  override suspend fun latestBuild(mcVersion: String): String? = notFoundAsNull {
    fetcher.json("$api/$mcVersion").obj()?.get("builds").obj()?.str("latest")
  }

  override suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String {
    val id = build ?: latestBuild(server.mcVersion)
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Purpur", server.mcVersion))
    val md5 = notFoundAsNull { fetcher.json("$api/${server.mcVersion}/$id").obj()?.str("md5") }
    downloadVerified(
      "$api/${server.mcVersion}/$id/download", File(env.dir, "server.jar"), "MD5", md5, env,
      context.getString(R.string.eng_dl_server, "Purpur", "${server.mcVersion} #$id"),
    )
    return "server.jar"
  }

  override suspend fun updateAvailable(server: ServerEntity): String? {
    val current = server.engineBuild?.toIntOrNull() ?: return null
    val latest = latestBuild(server.mcVersion)?.toIntOrNull() ?: return null
    return if (latest > current) "Build $latest" else null
  }
}
