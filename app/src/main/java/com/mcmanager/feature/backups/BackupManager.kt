package com.mcmanager.feature.backups

import android.content.Context
import android.net.Uri
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.BackupDao
import com.mcmanager.core.data.db.BackupEntity
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.util.FileUtils
import com.mcmanager.feature.network.ServerProps
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class BackupManager @Inject constructor(
  @ApplicationContext private val context: Context,
  private val servers: ServerRepository,
  private val dao: BackupDao,
  private val controller: ServerController,
) {
  private val _progress = MutableStateFlow<Map<Long, Float?>>(emptyMap())

  /** serverId -> fraction (null = indeterminate). Present only while an operation runs. */
  val progress: StateFlow<Map<Long, Float?>> = _progress

  private val locks = ConcurrentHashMap<Long, Mutex>()
  private fun lock(id: Long) = locks.getOrPut(id) { Mutex() }

  fun backupsDir(serverId: Long) = File(context.filesDir, "backups/$serverId")

  private fun setProgress(id: Long, p: Float?) = _progress.update { it + (id to p) }
  private fun clearProgress(id: Long) = _progress.update { it - id }

  private inline fun <T> safe(block: () -> T): Result<T> = try {
    Result.success(block())
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    Result.failure(e)
  }

  private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

  suspend fun createBackup(serverId: Long, label: String, auto: Boolean = false): Result<BackupEntity> =
    withContext(Dispatchers.IO) {
      safe {
        lock(serverId).withLock {
          val server = servers.get(serverId) ?: throw IOException("Server not found")
          val dir = File(server.dir)
          if (!dir.isDirectory) throw IOException("Server folder is missing")
          setProgress(serverId, null)
          try {
            val running = controller.runtime(serverId).value.state == ServerState.RUNNING
            if (running) flushWorld(serverId)
            try {
              val level = ServerProps.levelName(server.dir)
              val cleanLabel = label.trim().ifEmpty { if (auto) "auto" else "manual" }
              val out = File(backupsDir(serverId), "${stamp()}-${FileUtils.slug(cleanLabel)}.zip")
              FileUtils.zipDirectory(
                dir, out,
                includeFilter = { f -> includeFile(dir, f, level, server) },
                onProgress = { setProgress(serverId, it) },
              )
              val entity = BackupEntity(
                serverId = serverId, file = out.absolutePath, sizeBytes = out.length(),
                createdAt = System.currentTimeMillis(), label = cleanLabel, auto = auto,
              )
              val id = dao.insert(entity)
              applyRetention(serverId)
              entity.copy(id = id)
            } finally {
              if (running) controller.sendCommand(serverId, "save-on")
            }
          } finally {
            clearProgress(serverId)
          }
        }
      }
    }

  private fun includeFile(dir: File, f: File, level: String, server: ServerEntity): Boolean {
    val rel = f.relativeTo(dir).invariantSeparatorsPath
    return BackupFilter.include(rel, f.isDirectory, level, server.launchSpec)
  }

  /** save-off + save-all flush, then wait up to 60 s for the "Saved the game" console line. */
  private suspend fun flushWorld(serverId: Long) {
    val base = controller.console(serverId).value.maxOfOrNull { it.seq } ?: -1L
    controller.sendCommand(serverId, "save-off")
    controller.sendCommand(serverId, "save-all flush")
    withTimeoutOrNull(60_000) {
      controller.console(serverId).first { lines ->
        lines.any { it.seq > base && (it.text.contains("Saved the game") || it.text.contains("Saved the world")) }
      }
    }
  }

  private suspend fun find(backupId: Long): BackupEntity? {
    for (s in servers.getAll()) dao.listFor(s.id).firstOrNull { it.id == backupId }?.let { return it }
    return null
  }

  suspend fun restore(backupId: Long): Result<Unit> = withContext(Dispatchers.IO) {
    safe {
      val b = find(backupId) ?: throw IOException("Backup not found")
      val server = servers.get(b.serverId) ?: throw IOException("Server not found")
      val state = controller.runtime(server.id).value.state
      if (state != ServerState.STOPPED && state != ServerState.CRASHED) {
        throw IllegalStateException("Stop the server before restoring")
      }
      val zip = File(b.file)
      if (!zip.isFile) throw IOException("Backup file is missing")
      lock(server.id).withLock {
        setProgress(server.id, null)
        try {
          val dir = File(server.dir)
          val tops = ZipFile(zip).use { zf ->
            zf.entries().asSequence().map { it.name.trimStart('/').substringBefore('/') }
              .filter { it.isNotEmpty() && it != ".." }.toSet()
          }
          val oldDir = File(dir, ".mcmanager/restore-old-${stamp()}")
          val moved = mutableListOf<String>()
          for (name in tops) {
            val cur = File(dir, name)
            if (!cur.exists()) continue
            oldDir.mkdirs()
            if (!cur.renameTo(File(oldDir, name))) throw IOException("Could not move $name aside")
            moved += name
          }
          try {
            FileUtils.unzip(zip, dir) { setProgress(server.id, it) }
          } catch (t: Throwable) {
            for (name in tops) File(dir, name).deleteRecursively()
            for (name in moved) File(oldDir, name).renameTo(File(dir, name))
            throw t
          }
        } finally {
          clearProgress(server.id)
        }
      }
    }
  }

  suspend fun delete(backupId: Long) {
    withContext(Dispatchers.IO) {
      val b = find(backupId) ?: return@withContext
      File(b.file).delete()
      dao.delete(b)
    }
  }

  /** Keeps the newest `server.backupKeep` AUTO backups; manual ones are never removed. */
  suspend fun applyRetention(serverId: Long) {
    withContext(Dispatchers.IO) {
      val server = servers.get(serverId) ?: return@withContext
      val keep = server.backupKeep.coerceAtLeast(1)
      dao.listFor(serverId).filter { it.auto }.sortedByDescending { it.createdAt }.drop(keep).forEach {
        File(it.file).delete()
        dao.delete(it)
      }
    }
  }

  /** Copies backup [backupId] to a SAF destination. */
  suspend fun export(backupId: Long, dest: Uri): Result<Unit> = withContext(Dispatchers.IO) {
    safe {
      val b = find(backupId) ?: throw IOException("Backup not found")
      val src = File(b.file)
      if (!src.isFile) throw IOException("Backup file is missing")
      setProgress(b.serverId, 0f)
      try {
        val total = src.length().coerceAtLeast(1)
        var done = 0L
        val out = context.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Cannot open destination")
        out.buffered().use { o ->
          src.inputStream().use { i ->
            val buf = ByteArray(128 * 1024)
            while (true) {
              val n = i.read(buf)
              if (n < 0) break
              o.write(buf, 0, n)
              done += n
              setProgress(b.serverId, done.toFloat() / total)
            }
          }
        }
      } finally {
        clearProgress(b.serverId)
      }
    }
  }

  /** Copies a zip picked via SAF into the backups folder and records it as "Imported". */
  suspend fun import(serverId: Long, src: Uri): Result<BackupEntity> = withContext(Dispatchers.IO) {
    safe {
      servers.get(serverId) ?: throw IOException("Server not found")
      setProgress(serverId, null)
      try {
        val out = File(backupsDir(serverId), "${stamp()}-imported.zip")
        out.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(src) ?: throw IOException("Cannot open file")
        input.use { i -> out.outputStream().buffered().use { o -> i.copyTo(o, 128 * 1024) } }
        try {
          ZipFile(out).use { }
        } catch (e: Exception) {
          out.delete()
          throw IOException("Not a valid zip file")
        }
        val e = BackupEntity(
          serverId = serverId, file = out.absolutePath, sizeBytes = out.length(),
          createdAt = System.currentTimeMillis(), label = "Imported", auto = false,
        )
        e.copy(id = dao.insert(e))
      } finally {
        clearProgress(serverId)
      }
    }
  }
}
