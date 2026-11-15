package com.mcmanager.engine

import android.content.Context
import com.mcmanager.core.net.HttpException
import com.mcmanager.core.net.getString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/** GET with in-memory + disk cache (default TTL 1 h). Falls back to a stale copy when offline. */
@Singleton
class CachedFetcher @Inject constructor(
  @ApplicationContext context: Context,
  private val http: OkHttpClient,
  private val json: Json,
) {
  private val dir = File(context.cacheDir, "mcm_http").also { it.mkdirs() }
  private val mem = ConcurrentHashMap<String, Pair<Long, String>>()

  suspend fun text(url: String, ttlMs: Long = TTL_DEFAULT): String {
    val now = System.currentTimeMillis()
    mem[url]?.let { (t, body) -> if (now - t < ttlMs) return body }
    val file = fileFor(url)
    withContext(Dispatchers.IO) {
      if (file.isFile && now - file.lastModified() < ttlMs) runCatching { file.readText() }.getOrNull() else null
    }?.let { body ->
      mem[url] = file.lastModified() to body
      return body
    }
    try {
      val body = http.getString(url)
      val t = System.currentTimeMillis()
      mem[url] = t to body
      withContext(Dispatchers.IO) { runCatching { file.writeText(body) } }
      return body
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      // A definite client error (404 ...) must not be hidden by stale data.
      if (e is HttpException && e.code in 400..499) throw e
      mem[url]?.let { return it.second }
      val stale = withContext(Dispatchers.IO) { if (file.isFile) runCatching { file.readText() }.getOrNull() else null }
      if (stale != null) return stale
      throw e
    }
  }

  suspend fun json(url: String, ttlMs: Long = TTL_DEFAULT): JsonElement =
    json.parseToJsonElement(text(url, ttlMs))

  private fun fileFor(url: String): File {
    val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(dir, "$h.json")
  }

  companion object {
    const val TTL_DEFAULT = 60 * 60 * 1000L
    const val TTL_LONG = 30L * 24 * 60 * 60 * 1000L
  }
}
