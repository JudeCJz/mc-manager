package com.mcmanager.core.util

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object FileUtils {

  /** Lowercase, filesystem-safe name ("My World!" -> "my-world"). Never empty. */
  fun slug(name: String): String =
    name.trim().lowercase()
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .take(40)
      .ifEmpty { "server" }

  /** Total size of all files below [dir] (blocking; call off the main thread). */
  fun dirSize(dir: File): Long =
    if (dir.isFile) dir.length() else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  /**
   * Zips [src] into [outZip] (entries relative to [src]). [includeFilter] receives each file or
   * directory; return false to skip it (and, for directories, everything below). [onProgress] gets 0..1.
   * Writes to a temp file and renames on success.
   */
  suspend fun zipDirectory(
    src: File,
    outZip: File,
    includeFilter: (File) -> Boolean = { true },
    onProgress: (Float) -> Unit = {},
  ) = withContext(Dispatchers.IO) {
    outZip.parentFile?.mkdirs()
    val tmp = File(outZip.parentFile, outZip.name + ".part")
    try {
      val files = src.walkTopDown()
        .onEnter { it == src || includeFilter(it) }
        .filter { it != src && it.isFile && includeFilter(it) }
        .toList()
      val total = files.sumOf { it.length() }.coerceAtLeast(1L)
      var done = 0L
      ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
        val buf = ByteArray(64 * 1024)
        for (f in files) {
          ensureActive()
          val name = f.relativeTo(src).invariantSeparatorsPath
          zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
          f.inputStream().use { input ->
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              zip.write(buf, 0, n)
              done += n
              onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
          }
          zip.closeEntry()
        }
      }
      if (outZip.exists()) outZip.delete()
      if (!tmp.renameTo(outZip)) throw IOException("Could not create ${outZip.name}")
      onProgress(1f)
    } catch (t: Throwable) {
      tmp.delete()
      throw t
    }
  }

  /** Extracts [zip] into [destDir]. Entries escaping [destDir] (zip-slip) are rejected with an IOException. */
  suspend fun unzip(zip: File, destDir: File, onProgress: (Float) -> Unit = {}) =
    withContext(Dispatchers.IO) {
      destDir.mkdirs()
      val root = destDir.canonicalFile
      val total = zip.length().coerceAtLeast(1L)
      var read = 0L
      ZipInputStream(zip.inputStream().buffered()).use { zin ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          ensureActive()
          val entry = zin.nextEntry ?: break
          val target = File(root, entry.name).canonicalFile
          if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Blocked unsafe zip entry: ${entry.name}")
          }
          if (entry.isDirectory) {
            target.mkdirs()
          } else {
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { out ->
              while (true) {
                val n = zin.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                read += n
              }
            }
            if (entry.time > 0) target.setLastModified(entry.time)
          }
          zin.closeEntry()
          onProgress((read.toFloat() / (total * 3)).coerceIn(0f, 0.99f))
        }
      }
      onProgress(1f)
    }
}
