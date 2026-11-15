package com.mcmanager.core.data

import android.content.Context
import com.mcmanager.core.data.db.ServerDao
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.data.settings.StorageLocation
import com.mcmanager.core.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Singleton
class ServerRepository @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dao: ServerDao,
  private val settings: SettingsRepository,
) {
  fun observeAll(): Flow<List<ServerEntity>> = dao.observeAll()

  fun observe(id: Long): Flow<ServerEntity?> = dao.observe(id)

  suspend fun get(id: Long): ServerEntity? = dao.get(id)

  suspend fun getAll(): List<ServerEntity> = dao.getAll()

  /**
   * Inserts the server and creates its directory. If [entity].dir is blank the directory becomes
   * `<serverRoot>/<id>-<slug(name)>`. Returns the new id.
   */
  suspend fun create(entity: ServerEntity): Long {
    val id = dao.insert(entity)
    val dir = entity.dir.ifBlank {
      File(serverRootFor(settings.settings.first().storage), "$id-${FileUtils.slug(entity.name)}").absolutePath
    }
    File(dir).mkdirs()
    if (dir != entity.dir) dao.update(entity.copy(id = id, dir = dir))
    return id
  }

  suspend fun update(entity: ServerEntity) = dao.update(entity)

  suspend fun delete(id: Long, deleteFiles: Boolean) {
    val server = dao.get(id) ?: return
    dao.delete(server)
    if (deleteFiles) File(server.dir).deleteRecursively()
  }

  /**
   * Root folder for new servers according to the storage setting. Reads the DataStore once
   * (blocking, cheap); prefer calling off the main thread.
   */
  fun serverRoot(): File =
    serverRootFor(runBlocking { settings.settings.first().storage })

  private fun serverRootFor(storage: StorageLocation): File {
    val base = when (storage) {
      StorageLocation.INTERNAL -> context.filesDir
      StorageLocation.EXTERNAL_APP -> context.getExternalFilesDir(null) ?: context.filesDir
    }
    return File(base, "servers").also { it.mkdirs() }
  }
}
