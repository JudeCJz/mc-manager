package com.mcmanager.feature.server

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.nav.Routes
import com.mcmanager.core.util.NetUtils
import com.mcmanager.core.util.RamUtils
import com.mcmanager.feature.network.NetworkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HubUi(
  val loaded: Boolean = false,
  val server: ServerEntity? = null,
  val runtime: RuntimeState = RuntimeState(),
  val yamlFiles: List<String> = emptyList(),
  val lanIp: String? = null,
  val publicAddress: String? = null,
  val isBedrockActive: Boolean = false,
  val totalRamMb: Int = 0,
  val otherRunningXmxMb: Int = 0,
)

private data class BaseHubData(
  val server: ServerEntity?,
  val rt: RuntimeState,
  val others: Int,
  val hasBedrock: Boolean,
)

private val YAML_CANDIDATES = listOf("bukkit.yml", "spigot.yml", "config/paper-global.yml", "purpur.yml")

@HiltViewModel
class ServerHubViewModel @Inject constructor(
  @ApplicationContext context: Context,
  handle: SavedStateHandle,
  private val repo: ServerRepository,
  private val controller: ServerController,
  private val settingsRepo: SettingsRepository,
  private val netManager: NetworkManager,
) : ViewModel() {

  private val id: Long = handle.get<Long>(Routes.ARG_ID) ?: 0L
  private val totalRam = RamUtils.totalRamMb(context)
  private var lanCache: Pair<Long, String?> = 0L to null
  private val messages = Channel<String>(Channel.BUFFERED)
  val events = messages.receiveAsFlow()

  val pendingStart = MutableStateFlow(false)

  val ui: StateFlow<HubUi> = combine(
    repo.observe(id),
    controller.runtime(id),
    controller.runtimes,
    repo.observeAll(),
  ) { server, rt, all, servers ->
    val others = servers.filter {
      it.id != id && (all[it.id]?.state == ServerState.RUNNING || all[it.id]?.state == ServerState.STARTING)
    }.sumOf { it.xmxMb }
    val hasBedrock = server?.let { s ->
      File(s.dir, "plugins").listFiles()?.any { it.name.contains("Geyser", ignoreCase = true) } == true
    } ?: false
    BaseHubData(server, rt, others, hasBedrock)
  }.combine(settingsRepo.settings) { base, settings ->
    base to settings
  }.combine(netManager.playit.state) { (base, settings), playit ->
    val (server, rt, others, hasBedrock) = base
    val custom = settings.customPublicAddress.takeIf { it.isNotBlank() }
    val playitAddr = playit.addresses.firstOrNull()
    val pub = custom ?: playitAddr
    HubUi(
      loaded = true,
      server = server,
      runtime = rt,
      yamlFiles = if (server == null) emptyList() else YAML_CANDIDATES.filter { File(server.dir, it).isFile },
      lanIp = lanIp(),
      publicAddress = pub,
      isBedrockActive = hasBedrock,
      totalRamMb = totalRam,
      otherRunningXmxMb = others,
    )
  }.flowOn(Dispatchers.IO)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HubUi(totalRamMb = totalRam))

  private fun lanIp(): String? {
    val now = System.currentTimeMillis()
    if (now - lanCache.first > 10_000) lanCache = now to NetUtils.lanIpv4()
    return lanCache.second
  }

  fun onStart() {
    val s = ui.value.server ?: return
    if (totalRam > 0 && ui.value.otherRunningXmxMb + s.xmxMb > totalRam * 0.65) pendingStart.value = true else start()
  }

  fun confirmStart() {
    pendingStart.value = false
    start()
  }

  fun dismissStart() {
    pendingStart.value = false
  }

  private fun start() {
    viewModelScope.launch {
      controller.start(id).exceptionOrNull()?.let { messages.trySend(it.message.orEmpty()) }
    }
  }

  fun onStop() {
    viewModelScope.launch { controller.stop(id) }
  }

  fun onRestart() {
    viewModelScope.launch { controller.restart(id) }
  }

  fun onKill() = controller.kill(id)
}
