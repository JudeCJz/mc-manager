package com.mcmanager.feature.plugins

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tiny in-memory icon cache: OkHttp + BitmapFactory (no image library on the classpath). */
@Singleton
class IconLoader @Inject constructor(private val client: OkHttpClient) {
  private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
  }
  private val failed = HashSet<String>()

  suspend fun load(url: String): Bitmap? {
    cache.get(url)?.let { return it }
    if (synchronized(failed) { url in failed }) return null
    val bmp = withContext(Dispatchers.IO) {
      runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
          if (!resp.isSuccessful) return@use null
          val bytes = resp.body.bytes()
          val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
          var sample = 1
          while (opts.outWidth / sample > 192) sample *= 2
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
      }.getOrNull()
    }
    if (bmp != null) cache.put(url, bmp) else synchronized(failed) { failed += url }
    return bmp
  }
}
