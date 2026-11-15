package com.mcmanager.core.net

import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class HttpException(val code: Int, val url: String) : IOException("HTTP $code for $url")

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
  cont.invokeOnCancellation { cancel() }
  enqueue(object : Callback {
    override fun onFailure(call: Call, e: IOException) {
      if (!cont.isCancelled) cont.resumeWithException(e)
    }

    override fun onResponse(call: Call, response: Response) {
      cont.resume(response) { _, _, _ -> response.close() }
    }
  })
}

private fun get(url: String) = Request.Builder().url(url).get().build()

/** GET [url] and return the body as text. Throws [HttpException] on a non-2xx status. */
suspend fun OkHttpClient.getString(url: String): String =
  newCall(get(url)).await().use { resp ->
    if (!resp.isSuccessful) throw HttpException(resp.code, url)
    withContext(Dispatchers.IO) { resp.body.string() }
  }

/** GET [url] and decode the JSON body as [T]. */
suspend inline fun <reified T> OkHttpClient.getJson(json: Json, url: String): T =
  json.decodeFromString<T>(getString(url))

/**
 * Downloads [url] to [dest] (via `dest.part`, renamed on success). [onProgress] gets 0..1, or null when
 * the length is unknown. Runs on the IO dispatcher and is cancellable; the partial file is removed on failure.
 */
suspend fun OkHttpClient.download(url: String, dest: File, onProgress: (Float?) -> Unit) {
  val part = File(dest.parentFile, dest.name + ".part")
  withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    try {
      newCall(get(url)).execute().use { resp ->
        if (!resp.isSuccessful) throw HttpException(resp.code, url)
        val body = resp.body
        val total = body.contentLength()
        onProgress(if (total > 0) 0f else null)
        var done = 0L
        var lastReport = 0L
        body.byteStream().use { input ->
          part.outputStream().buffered().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
              ensureActive()
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)
              done += n
              val now = System.nanoTime()
              if (total > 0 && now - lastReport > 100_000_000L) {
                lastReport = now
                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
              }
            }
          }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} to ${dest.name}")
        onProgress(if (total > 0) 1f else null)
      }
    } catch (t: Throwable) {
      part.delete()
      throw t
    }
  }
}
