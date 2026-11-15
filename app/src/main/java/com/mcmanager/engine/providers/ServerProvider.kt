package com.mcmanager.engine.providers

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.core.net.HttpException
import com.mcmanager.core.net.download
import com.mcmanager.engine.CachedFetcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient

/** What a provider needs while installing. */
class InstallEnv(
  val dir: File,
  val onProgress: (InstallProgress) -> Unit,
  /** Resolves the java binary for the server (Forge installer needs it). */
  val javaFor: suspend (ServerEntity, (InstallProgress) -> Unit) -> File,
)

interface ServerProvider {
  val engine: Engine

  /** Newest first. */
  suspend fun mcVersions(includeSnapshots: Boolean): List<String>

  suspend fun latestBuild(mcVersion: String): String?

  /** Installs into [InstallEnv.dir]; returns the launch spec. */
  suspend fun install(server: ServerEntity, build: String?, env: InstallEnv): String

  /** e.g. "Build 131"; null = up to date or unknown. */
  suspend fun updateAvailable(server: ServerEntity): String? = null
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.str(key: String): String? = this[key].str()

abstract class BaseProvider(
  protected val context: Context,
  protected val http: OkHttpClient,
  protected val fetcher: CachedFetcher,
) : ServerProvider {

  /** Runs [block]; a 404 from the API becomes null. */
  protected suspend fun <T> notFoundAsNull(block: suspend () -> T): T? = try {
    block()
  } catch (e: HttpException) {
    if (e.code == 404) null else throw e
  }

  protected fun progress(env: InstallEnv, step: String, f: Float?) = env.onProgress(InstallProgress(step, f))

  /**
   * Downloads [url] to a temp file next to [dest], verifies the checksum when given (algo: SHA-256, SHA-1,
   * SHA-512, MD5), then moves it over [dest]. A bad download never replaces an existing file.
   */
  protected suspend fun downloadVerified(
    url: String,
    dest: File,
    algo: String?,
    expectedHex: String?,
    env: InstallEnv,
    label: String,
  ) {
    val tmp = File(dest.parentFile, dest.name + ".new")
    try {
      progress(env, label, 0f)
      http.download(url, tmp) { f -> progress(env, label, f) }
      if (algo != null && !expectedHex.isNullOrBlank()) {
        progress(env, context.getString(R.string.eng_verify), null)
        val actual = withContext(Dispatchers.IO) { Checksums.hex(tmp, algo) }
        if (!actual.equals(expectedHex.trim(), ignoreCase = true)) {
          throw IOException(context.getString(R.string.eng_err_checksum, dest.name))
        }
      }
      withContext(Dispatchers.IO) {
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not move ${tmp.name} to ${dest.name}")
      }
    } catch (e: CancellationException) {
      tmp.delete()
      throw e
    } catch (t: Throwable) {
      tmp.delete()
      throw t
    }
  }
}

object Checksums {
  fun hex(file: File, algo: String): String {
    val md = MessageDigest.getInstance(algo)
    file.inputStream().buffered().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
