package com.mcmanager.engine.providers

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.engine.CachedFetcher
import com.mcmanager.engine.JavaEnv
import com.mcmanager.engine.McVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Experimental: runs the official Forge installer with the bundled JRE. */
@Singleton
class ForgeProvider @Inject constructor(
  @ApplicationContext context: Context,
  http: OkHttpClient,
  fetcher: CachedFetcher,
) : BaseProvider(context, http, fetcher) {
  override val engine = Engine.FORGE

  private suspend fun promos(): Map<String, String> =
    fetcher.json("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
      .obj()?.get("promos").obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty()

  override suspend fun mcVersions(includeSnapshots: Boolean): List<String> =
    promos().keys.map { it.substringBeforeLast('-') }
      .distinct()
      .filter { McVersion.isRelease(it) && McVersion.compare(it, "1.12") >= 0 }
      .sortedWith(McVersion.newestFirst)

  override suspend fun latestBuild(mcVersion: String): String? {
    val p = promos()
    return p["$mcVersion-recommended"] ?: p["$mcVersion-latest"]
  }

  override suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String {
    val mc = server.mcVersion
    val ver = build ?: latestBuild(mc)
      ?: throw IOException(context.getString(R.string.eng_err_no_build, "Forge", mc))
    val full = "$mc-$ver"
    val installer = File(env.dir, "forge-installer.jar")
    downloadVerified(
      "https://maven.minecraftforge.net/net/minecraftforge/forge/$full/forge-$full-installer.jar",
      installer, null, null, env, context.getString(R.string.eng_dl_server, "Forge", full),
    )
    val java = env.javaFor(server, env.onProgress)
    val step = context.getString(R.string.eng_forge_installer)
    env.onProgress(InstallProgress(step, null))
    val exit = withContext(Dispatchers.IO) {
      val pb = ProcessBuilder(java.absolutePath, "-jar", installer.name, "--installServer")
        .directory(env.dir).redirectErrorStream(true)
      JavaEnv.apply(pb, JavaEnv.homeOf(java), context)
      val p = pb.start()
      val job = coroutineContext[Job]
      try {
        p.inputStream.bufferedReader().forEachLine { line ->
          if (line.isNotBlank()) env.onProgress(InstallProgress(line.trim().take(120), null))
          if (job != null && !job.isActive) throw CancellationException("cancelled")
        }
        p.waitFor()
      } finally {
        if (p.isAlive) p.destroyForcibly()
      }
    }
    coroutineContext.ensureActive()
    if (exit != 0) throw IOException(context.getString(R.string.eng_err_forge_failed, exit))
    installer.delete()
    File(env.dir, "forge-installer.jar.log").delete()

    val modern = McVersion.compare(mc, "1.17") >= 0
    val spec = if (modern) {
      "@libraries/net/minecraftforge/forge/$full/unix_args.txt"
    } else {
      env.dir.listFiles { f -> f.isFile && f.name.startsWith("forge-") && f.name.endsWith(".jar") && !f.name.contains("installer") }
        ?.minByOrNull { it.name.length }?.name
    }
    if (spec == null || !File(env.dir, spec.removePrefix("@")).isFile) {
      throw IOException(context.getString(R.string.eng_err_forge_missing))
    }
    return spec
  }

  override suspend fun updateAvailable(server: ServerEntity): String? {
    val current = server.engineBuild ?: return null
    val latest = latestBuild(server.mcVersion) ?: return null
    return if (McVersion.compare(latest, current) > 0) "Forge $latest" else null
  }
}
