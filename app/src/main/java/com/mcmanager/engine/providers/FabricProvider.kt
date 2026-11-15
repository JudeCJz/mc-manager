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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

@Singleton
class FabricProvider @Inject constructor(
  @ApplicationContext context: Context,
  http: OkHttpClient,
  fetcher: CachedFetcher,
) : BaseProvider(context, http, fetcher) {
  override val engine = Engine.FABRIC
  private val meta = "https://meta.fabricmc.net/v2/versions"

  override suspend fun mcVersions(includeSnapshots: Boolean): List<String> =
    fetcher.json("$meta/game").arr().orEmpty().mapNotNull { e ->
      val o = e.obj() ?: return@mapNotNull null
      val stable = o["stable"]?.jsonPrimitive?.content == "true"
      o.str("version")?.takeIf { includeSnapshots || stable }
    }

  private suspend fun newestStable(path: String): String? {
    val list = fetcher.json("$meta/$path").arr().orEmpty()
    fun JsonElement.isStable() = obj()?.get("stable")?.jsonPrimitive?.content == "true"
    val pick = list.firstOrNull { it.isStable() } ?: list.firstOrNull()
    return pick.obj()?.str("version")
  }

  override suspend fun latestBuild(mcVersion: String): String? = newestStable("loader")

  override suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String {
    val loader = build ?: newestStable("loader")
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Fabric", server.mcVersion))
    val installer = newestStable("installer")
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Fabric", server.mcVersion))
    downloadVerified(
      "$meta/loader/${server.mcVersion}/$loader/$installer/server/jar",
      File(env.dir, "fabric-server-launch.jar"), null, null, env,
      context.getString(R.string.eng_dl_server, "Fabric", "${server.mcVersion} (loader $loader)"),
    )
    return "fabric-server-launch.jar"
  }

  override suspend fun updateAvailable(server: ServerEntity): String? {
    val current = server.engineBuild ?: return null
    val latest = latestBuild(server.mcVersion) ?: return null
    return if (McVersion.compare(latest, current) > 0) "Loader $latest" else null
  }
}
