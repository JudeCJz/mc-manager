package com.mcmanager.feature.logs

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LogFile(val name: String, val path: String, val size: Long, val modified: Long)

data class LogsUi(
  val loadingFiles: Boolean = true,
  val files: List<LogFile> = emptyList(),
  val selected: LogFile? = null,
  val lines: List<String> = emptyList(),
  val readingFile: Boolean = false,
  val error: String? = null,
  val query: String = "",
  val matches: List<Int> = emptyList(),
  val matchPos: Int = 0,
)

private const val MAX_LINES = 20_000

@HiltViewModel
class LogsViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  handle: SavedStateHandle,
  private val repo: ServerRepository,
) : ViewModel() {

  private val id: Long = handle.get<Long>(Routes.ARG_ID) ?: 0L
  private val _ui = MutableStateFlow(LogsUi())
  val ui: StateFlow<LogsUi> = _ui.asStateFlow()
  private val messages = Channel<ExportResult>(Channel.BUFFERED)
  val exportEvents = messages.receiveAsFlow()
  private var readJob: Job? = null

  enum class ExportResult { OK, FAILED }

  init {
    loadFiles()
  }

  fun loadFiles() {
    viewModelScope.launch {
      _ui.update { it.copy(loadingFiles = true) }
      val files: List<LogFile> = withContext(Dispatchers.IO) {
        val server = repo.get(id) ?: return@withContext emptyList<LogFile>()
        File(server.dir, "logs").listFiles { f ->
          f.isFile && (f.name == "latest.log" || f.name.endsWith(".log") || f.name.endsWith(".log.gz"))
        }.orEmpty()
          .sortedByDescending { it.lastModified() }
          .map { LogFile(it.name, it.absolutePath, it.length(), it.lastModified()) }
      }
      _ui.update { it.copy(loadingFiles = false, files = files) }
    }
  }

  fun open(file: LogFile) {
    readJob?.cancel()
    _ui.update { it.copy(selected = file, lines = emptyList(), error = null, readingFile = true, query = "", matches = emptyList(), matchPos = 0) }
    readJob = viewModelScope.launch {
      var lastStamp = -1L
      try {
        while (true) {
          val f = File(file.path)
          val stamp = f.lastModified() xor (f.length() shl 20)
          if (stamp != lastStamp) {
            lastStamp = stamp
            val lines = withContext(Dispatchers.IO) { readLines(f) }
            _ui.update { it.copy(lines = lines, readingFile = false, error = null).withMatches() }
          }
          if (file.name != "latest.log") break
          delay(2000)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _ui.update { it.copy(readingFile = false, error = e.message.orEmpty()) }
      }
    }
  }

  fun close() {
    readJob?.cancel()
    _ui.update { it.copy(selected = null, lines = emptyList(), query = "", matches = emptyList(), error = null) }
    loadFiles()
  }

  fun setQuery(q: String) {
    _ui.update { it.copy(query = q, matchPos = 0).withMatches() }
  }

  fun nextMatch() = _ui.update { if (it.matches.isEmpty()) it else it.copy(matchPos = (it.matchPos + 1) % it.matches.size) }

  fun prevMatch() = _ui.update {
    if (it.matches.isEmpty()) it else it.copy(matchPos = (it.matchPos - 1 + it.matches.size) % it.matches.size)
  }

  fun exportTo(uri: Uri) {
    val sel = _ui.value.selected ?: return
    viewModelScope.launch {
      val ok = withContext(Dispatchers.IO) {
        runCatching {
          context.contentResolver.openOutputStream(uri)?.use { out -> File(sel.path).inputStream().use { it.copyTo(out) } }
            ?: error("no stream")
        }.isSuccess
      }
      messages.trySend(if (ok) ExportResult.OK else ExportResult.FAILED)
    }
  }

  private fun LogsUi.withMatches(): LogsUi {
    val q = query.trim()
    if (q.isEmpty()) return copy(matches = emptyList(), matchPos = 0)
    val m = lines.indices.filter { lines[it].contains(q, ignoreCase = true) }
    return copy(matches = m, matchPos = matchPos.coerceIn(0, (m.size - 1).coerceAtLeast(0)))
  }

  private fun readLines(f: File): List<String> {
    val input = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()
    val ring = ArrayDeque<String>()
    input.bufferedReader().useLines { seq ->
      for (line in seq) {
        ring.addLast(line)
        if (ring.size > MAX_LINES) ring.removeFirst()
      }
    }
    return ring.toList()
  }
}
