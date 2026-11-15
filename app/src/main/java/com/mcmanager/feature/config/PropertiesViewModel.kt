package com.mcmanager.feature.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ServerState
import com.mcmanager.feature.files.FileOps
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PropsUiState(
  val loading: Boolean = true,
  val serverMissing: Boolean = false,
  val fileExists: Boolean = false,
  /** Values currently in the file. */
  val base: Map<String, String> = emptyMap(),
  /** Unsaved edits (only entries that differ from the effective baseline). */
  val edits: Map<String, String> = emptyMap(),
  val extraKeys: List<String> = emptyList(),
  val serverState: ServerState = ServerState.STOPPED,
  val saving: Boolean = false,
) {
  val portEditable: Boolean get() = serverState == ServerState.STOPPED || serverState == ServerState.CRASHED
  val running: Boolean
    get() = serverState == ServerState.RUNNING || serverState == ServerState.STARTING

  fun baseline(def: PropDef): String = base[def.key] ?: def.default
  fun value(def: PropDef): String = edits[def.key] ?: baseline(def)
  fun value(key: String): String = edits[key] ?: base[key] ?: ""
  fun hasErrors(): Boolean = edits.any { (k, v) -> PropertiesSchema.def(k)?.let { PropertiesSchema.validate(it, v) } != null }
}

sealed interface PropsEvent {
  data class Saved(val restartNeeded: Boolean) : PropsEvent
  data class Failed(val message: String) : PropsEvent
}

@HiltViewModel
class PropertiesViewModel @Inject constructor(
  private val servers: ServerRepository,
  private val controller: ServerController,
) : ViewModel() {

  private val _state = MutableStateFlow(PropsUiState())
  val state: StateFlow<PropsUiState> = _state.asStateFlow()

  private val _events = Channel<PropsEvent>(Channel.BUFFERED)
  val events = _events.receiveAsFlow()

  private var serverId = -1L
  private var server: ServerEntity? = null
  private var file: PropertiesFile = PropertiesFile.empty()

  private fun propsFile(): File? = server?.let { File(it.dir, "server.properties") }

  fun init(id: Long) {
    if (serverId == id) return
    serverId = id
    viewModelScope.launch {
      server = servers.get(id)
      if (server == null) {
        _state.update { it.copy(loading = false, serverMissing = true) }
        return@launch
      }
      launch { controller.runtime(id).collectLatest { rt -> _state.update { it.copy(serverState = rt.state) } } }
      load()
    }
  }

  fun load() {
    viewModelScope.launch {
      val f = propsFile() ?: return@launch
      val text = withContext(Dispatchers.IO) { if (f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null }
      file = if (text != null) PropertiesFile.parse(text) else PropertiesFile.empty()
      publishBase(text != null)
    }
  }

  private fun publishBase(exists: Boolean) {
    val map = file.toMap()
    val extra = map.keys.filter { PropertiesSchema.def(it) == null }
    _state.update { it.copy(loading = false, fileExists = exists, base = map, edits = emptyMap(), extraKeys = extra) }
  }

  fun edit(key: String, value: String) {
    _state.update { s ->
      val baseline = s.base[key] ?: PropertiesSchema.def(key)?.default ?: ""
      val e = s.edits.toMutableMap()
      if (value == baseline) e.remove(key) else e[key] = value
      s.copy(edits = e)
    }
  }

  fun discard() = _state.update { it.copy(edits = emptyMap()) }

  fun createDefaults() {
    viewModelScope.launch {
      val f = propsFile() ?: return@launch
      try {
        withContext(Dispatchers.IO) {
          val d = PropertiesSchema.defaultsFile()
          server?.let { d.set("server-port", it.port.toString()) }
          FileOps.atomicWrite(f, d.render())
        }
        load()
      } catch (e: Exception) {
        _events.trySend(PropsEvent.Failed(e.message ?: "error"))
      }
    }
  }

  fun save() {
    val s = _state.value
    val f = propsFile() ?: return
    val srv = server ?: return
    if (s.edits.isEmpty() || s.hasErrors()) return
    viewModelScope.launch {
      _state.update { it.copy(saving = true) }
      try {
        val running = s.running
        withContext(Dispatchers.IO) {
          // re-read so we don't clobber outside changes to other keys
          val cur = if (f.isFile) PropertiesFile.parse(f.readText(Charsets.UTF_8)) else PropertiesFile.empty()
          for ((k, v) in s.edits) cur.set(k, v)
          FileOps.atomicWrite(f, cur.render())
          file = cur
        }
        s.edits["server-port"]?.toIntOrNull()?.let { p ->
          if (s.portEditable && p != srv.port) {
            val updated = srv.copy(port = p)
            servers.update(updated)
            server = updated
          }
        }
        publishBase(true)
        _events.trySend(PropsEvent.Saved(restartNeeded = running))
      } catch (e: Exception) {
        _events.trySend(PropsEvent.Failed(e.message ?: "error"))
      }
      _state.update { it.copy(saving = false) }
    }
  }

  fun restart() {
    viewModelScope.launch { controller.restart(serverId) }
  }
}
