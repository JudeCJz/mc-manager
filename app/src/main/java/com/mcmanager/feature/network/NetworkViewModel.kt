package com.mcmanager.feature.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.AppSettings
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.RuntimeState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMsg(val res: Int, val arg: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NetworkViewModel @Inject constructor(
  private val manager: NetworkManager,
  private val servers: ServerRepository,
  private val controller: ServerController,
  private val settingsRepo: SettingsRepository,
) : ViewModel() {

  private val idFlow = MutableStateFlow(-1L)

  val server: StateFlow<ServerEntity?> = idFlow.flatMapLatest { servers.observe(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  val runtime: StateFlow<RuntimeState> = idFlow.flatMapLatest { controller.runtime(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RuntimeState())
  val settings: StateFlow<AppSettings> = settingsRepo.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
  val prefs: StateFlow<NetPrefs> = manager.prefs
  val playit: StateFlow<AgentState> = manager.playit.state
  val ngrok: StateFlow<AgentState> = manager.ngrok.state

  val forward: StateFlow<ForwardStatus> = idFlow.flatMapLatest { id ->
    kotlinx.coroutines.flow.flow {
      manager.forward.collect { emit(it[id] ?: ForwardStatus()) }
    }
  }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ForwardStatus())

  private val _info = MutableStateFlow<NetInfo?>(null)
  val info: StateFlow<NetInfo?> = _info

  private val _latencyDiff = MutableStateFlow<List<ServerProps.Change>?>(null)
  val latencyDiff: StateFlow<List<ServerProps.Change>?> = _latencyDiff

  private val _messages = MutableSharedFlow<UiMsg>(extraBufferCapacity = 8)
  val messages: SharedFlow<UiMsg> = _messages

  fun bind(serverId: Long) {
    if (idFlow.value == serverId) return
    idFlow.value = serverId
    manager.init()
    refreshInfo()
  }

  private val id get() = idFlow.value

  fun refreshInfo() {
    viewModelScope.launch { _info.value = manager.netInfo() }
  }

  fun setForward(on: Boolean) = viewModelScope.launch { manager.setForwardEnabled(id, on) }
  fun setUdp(on: Boolean) = viewModelScope.launch { manager.setUdp(id, on) }
  fun setRemoveOnStop(on: Boolean) = manager.setRemoveOnStop(id, on)
  fun check() = viewModelScope.launch { manager.checkReachability(id); refreshInfo() }

  fun changePort(text: String) {
    val p = text.trim().toIntOrNull()
    if (p == null) {
      _messages.tryEmit(UiMsg(R.string.ops_port_invalid)); return
    }
    viewModelScope.launch {
      val r = manager.changePort(id, p)
      _messages.tryEmit(
        if (r.isSuccess) UiMsg(R.string.ops_port_saved) else UiMsg(R.string.ops_error_fmt, r.exceptionOrNull()?.message),
      )
    }
  }

  fun prepareLatency() {
    viewModelScope.launch { _latencyDiff.value = manager.latencyDiff(id) }
  }

  fun dismissLatency() { _latencyDiff.value = null }

  fun applyLatency() {
    _latencyDiff.value = null
    viewModelScope.launch {
      val r = manager.applyLatency(id)
      _messages.tryEmit(
        if (r.isSuccess) UiMsg(R.string.ops_latency_applied) else UiMsg(R.string.ops_error_fmt, r.exceptionOrNull()?.message),
      )
    }
  }

  fun startPlayit() = manager.playit.start()
  fun stopPlayit() = manager.playit.stop()
  fun resetPlayit() = manager.playit.resetSecret()
  fun savePlayitSecret(secret: String) {
    manager.playit.saveSecret(secret)
    _messages.tryEmit(UiMsg(R.string.ops_playit_secret_saved))
  }
  fun hasPlayitSecret(): Boolean = manager.playit.hasSecret()

  fun saveCustomPublicAddress(addr: String) {
    viewModelScope.launch {
      settingsRepo.update { it.copy(customPublicAddress = addr.trim()) }
      _messages.tryEmit(UiMsg(R.string.ops_custom_tunnel_saved))
    }
  }

  fun saveNgrokToken(token: String) {
    viewModelScope.launch { settingsRepo.update { it.copy(ngrokAuthToken = token.trim()) } }
  }

  fun startNgrok() {
    val s = server.value ?: return
    manager.ngrok.start(s.port, settings.value.ngrokAuthToken, prefs.value.ngrokRegion)
  }

  fun stopNgrok() = manager.ngrok.stop()
  fun setNgrokRegion(r: String) = manager.setNgrokRegion(r)
  fun setTunnelsStop(v: Boolean) = manager.setTunnelsStopWithServers(v)
}
