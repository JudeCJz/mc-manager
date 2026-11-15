package com.mcmanager.feature.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.engine.ServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileEntry(
  val name: String,
  val rel: String,
  val isDir: Boolean,
  val size: Long,
  val modified: Long,
  val isText: Boolean,
)

data class UploadConflict(val uris: List<Uri>, val names: List<String>)

data class FilesUiState(
  val loading: Boolean = true,
  val serverMissing: Boolean = false,
  val path: String = "",
  val entries: List<FileEntry> = emptyList(),
  val serverRunning: Boolean = false,
  val busy: String? = null,
  val progress: Float? = null,
  val conflict: UploadConflict? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val servers: ServerRepository,
  private val controller: ServerController,
) : ViewModel() {

  private val _state = MutableStateFlow(FilesUiState())
  val state: StateFlow<FilesUiState> = _state.asStateFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  private var serverId = -1L
  private var root: File? = null
  private var watchJob: Job? = null

  fun init(id: Long, startPath: String) {
    if (serverId == id) return
    serverId = id
    viewModelScope.launch {
      val server = servers.get(id)
      if (server == null) {
        _state.update { it.copy(loading = false, serverMissing = true) }
        return@launch
      }
      val dir = File(server.dir)
      root = dir
      val start = withContext(Dispatchers.IO) {
        val r = FileOps.resolveInside(dir, startPath)
        if (r != null && r.isDirectory) FileOps.relativePath(dir, r) else ""
      }
      watchJob = launch {
        controller.runtime(id).collectLatest { rt ->
          _state.update { it.copy(serverRunning = rt.state == ServerState.RUNNING || rt.state == ServerState.STARTING) }
        }
      }
      open(start)
    }
  }

  private fun msg(res: Int, vararg args: Any) {
    _messages.trySend(context.getString(res, *args))
  }

  fun refresh() = open(_state.value.path)

  fun up() {
    val p = _state.value.path
    if (p.isEmpty()) return
    open(p.substringBeforeLast('/', ""))
  }

  fun open(rel: String) {
    val r = root ?: return
    viewModelScope.launch {
      _state.update { it.copy(loading = true) }
      val result = withContext(Dispatchers.IO) {
        val dir = FileOps.resolveInside(r, rel)
        if (dir == null || !dir.isDirectory) return@withContext null
        val base = FileOps.relativePath(r, dir)
        val list = (dir.listFiles() ?: emptyArray()).map { f ->
          FileEntry(
            name = f.name,
            rel = if (base.isEmpty()) f.name else base + "/" + f.name,
            isDir = f.isDirectory,
            size = if (f.isDirectory) 0L else f.length(),
            modified = f.lastModified(),
            isText = f.isFile && FileOps.isTextByExtension(f),
          )
        }.sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))
        FileOps.relativePath(r, dir) to list
      }
      if (result == null) {
        msg(R.string.files_err_outside)
        _state.update { it.copy(loading = false) }
      } else {
        _state.update { it.copy(loading = false, path = result.first, entries = result.second) }
      }
    }
  }

  private fun runIo(block: suspend () -> Unit) {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { block() }
      } catch (e: Exception) {
        _messages.trySend(e.message ?: context.getString(R.string.error_generic))
      }
      refresh()
    }
  }

  private fun child(name: String): File? {
    val r = root ?: return null
    val cur = FileOps.resolveInside(r, _state.value.path) ?: return null
    if (!FileOps.isValidName(name)) return null
    return FileOps.resolveInside(r, FileOps.relativePath(r, cur).let { if (it.isEmpty()) name else "$it/$name" })
  }

  fun createFolder(name: String) {
    val f = child(name) ?: return msg(R.string.files_err_name)
    if (f.exists()) return msg(R.string.files_err_exists, name)
    runIo { if (!f.mkdirs()) throw java.io.IOException(context.getString(R.string.error_generic)) }
  }

  fun createFile(name: String) {
    val f = child(name) ?: return msg(R.string.files_err_name)
    if (f.exists()) return msg(R.string.files_err_exists, name)
    runIo { if (!f.createNewFile()) throw java.io.IOException(context.getString(R.string.error_generic)) }
  }

  fun rename(e: FileEntry, newName: String) {
    val r = root ?: return
    val src = FileOps.resolveInside(r, e.rel) ?: return
    val dst = child(newName) ?: return msg(R.string.files_err_name)
    if (dst.exists()) return msg(R.string.files_err_exists, newName)
    runIo { if (!src.renameTo(dst)) throw java.io.IOException(context.getString(R.string.error_generic)) }
  }

  fun delete(e: FileEntry) {
    val r = root ?: return
    val f = FileOps.resolveInside(r, e.rel) ?: return
    if (f == r.canonicalFile) return
    runIo {
      val ok = if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete() else f.deleteRecursively()
      if (!ok) throw java.io.IOException(context.getString(R.string.error_generic))
    }
  }

  fun checkEditable(e: FileEntry, onEdit: (String) -> Unit) {
    val r = root ?: return
    viewModelScope.launch {
      val res = withContext(Dispatchers.IO) {
        val f = FileOps.resolveInside(r, e.rel) ?: return@withContext 0
        when {
          f.length() > MAX_EDIT_BYTES -> 1
          FileOps.isEditable(f) -> 2
          else -> 3
        }
      }
      when (res) {
        1 -> msg(R.string.files_err_too_big)
        2 -> onEdit(e.rel)
        3 -> msg(R.string.files_err_not_text)
        else -> Unit
      }
    }
  }

  private fun displayName(uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0)?.let { return it }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
  }

  fun upload(uris: List<Uri>, overwrite: Boolean) {
    val r = root ?: return
    if (uris.isEmpty()) return
    viewModelScope.launch {
      val dir = FileOps.resolveInside(r, _state.value.path) ?: return@launch
      val names = withContext(Dispatchers.IO) { uris.map { displayName(it) } }
      if (names.any { !FileOps.isValidName(it) }) return@launch msg(R.string.files_err_name)
      if (!overwrite) {
        val conflicts = names.filter { File(dir, it).exists() }
        if (conflicts.isNotEmpty()) {
          _state.update { it.copy(conflict = UploadConflict(uris, conflicts)) }
          return@launch
        }
      }
      _state.update { it.copy(conflict = null) }
      try {
        withContext(Dispatchers.IO) {
          uris.forEachIndexed { i, uri ->
            val name = names[i]
            _state.update { it.copy(busy = context.getString(R.string.files_uploading, i + 1, uris.size, name), progress = i.toFloat() / uris.size) }
            val target = File(dir, name)
            val tmp = File(dir, "$name.upload")
            try {
              context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
              }
              if (target.exists()) target.delete()
              if (!tmp.renameTo(target)) throw java.io.IOException(name)
            } finally {
              tmp.delete()
            }
          }
        }
        msg(R.string.files_uploaded, uris.size)
      } catch (e: Exception) {
        _messages.trySend(e.message ?: context.getString(R.string.error_generic))
      }
      _state.update { it.copy(busy = null, progress = null) }
      refresh()
    }
  }

  fun cancelConflict() = _state.update { it.copy(conflict = null) }

  fun export(e: FileEntry, dest: Uri) {
    val r = root ?: return
    val f = FileOps.resolveInside(r, e.rel) ?: return
    viewModelScope.launch {
      _state.update { it.copy(busy = context.getString(R.string.files_exporting, e.name), progress = null) }
      try {
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(dest)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        }
        msg(R.string.files_exported)
      } catch (ex: Exception) {
        _messages.trySend(ex.message ?: context.getString(R.string.error_generic))
      }
      _state.update { it.copy(busy = null) }
    }
  }
}
