package com.mcmanager.engine

import android.content.Context
import android.system.Os
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.JavaRuntimeManager
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.core.model.JavaRuntime
import com.mcmanager.core.net.download
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

@Singleton
class JavaRuntimeManagerImpl @Inject constructor(
  @ApplicationContext private val context: Context,
  private val http: OkHttpClient,
  private val settings: SettingsRepository,
) : JavaRuntimeManager {

  private val root = File(context.filesDir, "runtimes")
  private val locks = ConcurrentHashMap<Int, Mutex>()

  override fun installed(): List<JavaRuntime> =
    SUPPORTED.mapNotNull { existing(it) }

  override suspend fun ensure(major: Int, onProgress: (InstallProgress) -> Unit): JavaRuntime {
    val m = normalize(major)
    return locks.getOrPut(m) { Mutex() }.withLock {
      existing(m) ?: withContext(Dispatchers.IO) { install(m, onProgress) }
    }
  }

  override suspend fun remove(major: Int) {
    val m = normalize(major)
    locks.getOrPut(m) { Mutex() }.withLock {
      withContext(Dispatchers.IO) {
        File(root, "jre$m").deleteRecursively()
        File(root, "jre$m.tmp").deleteRecursively()
      }
    }
  }

  override suspend fun javaFor(server: ServerEntity, onProgress: (InstallProgress) -> Unit): File {
    server.customJavaPath?.takeIf { it.isNotBlank() }?.let { return resolveCustom(it) }
    settings.settings.first().globalJavaPath?.takeIf { it.isNotBlank() }?.let { return resolveCustom(it) }
    return ensure(server.javaMajor, onProgress).javaBin
  }

  private fun resolveCustom(path: String): File {
    var f = File(path)
    if (f.isDirectory) f = File(f, "bin/java")
    if (!f.isFile) throw IOException(context.getString(R.string.eng_err_java_missing, path))
    return f
  }

  private fun existing(m: Int): JavaRuntime? {
    val home = File(root, "jre$m")
    val bin = File(home, "bin/java")
    if (!bin.isFile) return null
    val version = runCatching { File(home, VERSION_FILE).readText().trim() }.getOrNull().orEmpty().ifEmpty { "Java $m" }
    return JavaRuntime(m, home, bin, version)
  }

  private suspend fun install(m: Int, onProgress: (InstallProgress) -> Unit): JavaRuntime {
    root.mkdirs()
    val archive = File(context.cacheDir, "jre$m-android-arm64.tar.xz")
    val tmp = File(root, "jre$m.tmp")
    val home = File(root, "jre$m")
    try {
      val dlStep = context.getString(R.string.eng_dl_java, m)
      onProgress(InstallProgress(dlStep, 0f))
      http.download("$BASE_URL/jre$m-android-arm64.tar.xz", archive) { f ->
        onProgress(InstallProgress(dlStep, f?.let { it * 0.6f }))
      }
      val exStep = context.getString(R.string.eng_unpack_java, m)
      onProgress(InstallProgress(exStep, 0.6f))
      tmp.deleteRecursively()
      tmp.mkdirs()
      extract(archive, tmp) { f -> onProgress(InstallProgress(exStep, 0.6f + f * 0.35f)) }
      fixPermissions(tmp)
      val chkStep = context.getString(R.string.eng_check_java, m)
      onProgress(InstallProgress(chkStep, 0.96f))
      val version = verify(m, tmp)
      File(tmp, VERSION_FILE).writeText(version)
      home.deleteRecursively()
      if (!tmp.renameTo(home)) throw IOException("Could not move ${tmp.name} to ${home.name}")
      onProgress(InstallProgress(chkStep, 1f))
      return JavaRuntime(m, home, File(home, "bin/java"), version)
    } catch (t: Throwable) {
      tmp.deleteRecursively()
      throw t
    } finally {
      archive.delete()
    }
  }

  private suspend fun extract(archive: File, dest: File, onFraction: (Float) -> Unit) {
    val rootPath = dest.absoluteFile.toPath().normalize()
    val total = archive.length().coerceAtLeast(1L)
    val counter = CountingInputStream(archive.inputStream().buffered(256 * 1024))
    var lastReport = 0L
    TarArchiveInputStream(XZCompressorInputStream(counter)).use { tar ->
      while (true) {
        coroutineContext.ensureActive()
        val entry: TarArchiveEntry = tar.nextEntry ?: break
        val name = entry.name.removePrefix("./").trimEnd('/')
        if (name.isEmpty()) continue
        val targetPath = rootPath.resolve(name).normalize()
        if (!targetPath.startsWith(rootPath)) throw IOException("Blocked unsafe archive entry: ${entry.name}")
        val target = targetPath.toFile()
        when {
          entry.isDirectory -> target.mkdirs()
          entry.isSymbolicLink -> {
            target.parentFile?.mkdirs()
            if (target.exists() || runCatching { Files.isSymbolicLink(targetPath) }.getOrDefault(false)) target.delete()
            Os.symlink(entry.linkName, target.absolutePath)
          }
          entry.isLink -> {
            val src = rootPath.resolve(entry.linkName.removePrefix("./")).normalize()
            if (!src.startsWith(rootPath)) throw IOException("Blocked unsafe archive link: ${entry.linkName}")
            target.parentFile?.mkdirs()
            Files.copy(src, targetPath, StandardCopyOption.REPLACE_EXISTING)
          }
          else -> {
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { out -> tar.copyTo(out) }
            target.setReadable(true, false)
            if (entry.mode and 0b001_001_001 != 0) target.setExecutable(true, false)
          }
        }
        val now = System.nanoTime()
        if (now - lastReport > 100_000_000L) {
          lastReport = now
          onFraction((counter.count.toFloat() / total).coerceIn(0f, 1f))
        }
      }
    }
    onFraction(1f)
  }

  private fun fixPermissions(home: File) {
    File(home, "bin").listFiles()?.forEach {
      it.setReadable(true, false)
      it.setExecutable(true, false)
    }
    File(context.applicationInfo.nativeLibraryDir, "libc++_shared.so").takeIf { it.isFile }?.let { src ->
      val dest = File(home, "lib/libc++_shared.so")
      if (!dest.exists()) {
        runCatching { src.copyTo(dest, overwrite = true) }
      }
    }
    home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach {
      it.setReadable(true, false)
      it.setExecutable(true, false)
    }
    File(home, "lib/jspawnhelper").takeIf { it.isFile }?.setExecutable(true, false)
  }

  /** Runs `bin/java -version`; returns the first non-blank line of its output. */
  private fun verify(m: Int, home: File): String {
    val java = File(home, "bin/java")
    val pb = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true)
    JavaEnv.apply(pb, home, context)
    val p = try {
      pb.start()
    } catch (e: IOException) {
      throw IOException(context.getString(R.string.eng_err_java_bad, m, e.message ?: ""), e)
    }
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw IOException(context.getString(R.string.eng_err_java_bad, m, "timeout"))
    }
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.exitValue() != 0) {
      throw IOException(context.getString(R.string.eng_err_java_bad, m, out.lineSequence().firstOrNull().orEmpty()))
    }
    return out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Java $m"
  }

  private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
      private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
      super.read(b, off, len).also { if (it > 0) count += it }
  }

  companion object {
    private const val BASE_URL = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download"
    private const val VERSION_FILE = ".mcm_version"
    private val SUPPORTED = listOf(8, 17, 21, 25)

    /** Maps any requested Java major onto the nearest runtime we ship (8, 17, 21, 25). */
    fun normalize(major: Int): Int = when {
      major <= 8 -> 8
      major <= 17 -> 17
      major <= 21 -> 21
      else -> 25
    }
  }
}
