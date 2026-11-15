package com.mcmanager.feature.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.engine.ServerInstaller
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.util.RamUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerCardUi(val server: ServerEntity, val runtime: RuntimeState, val update: String?)

data class DashboardUi(
  val loaded: Boolean = false,
  val cards: List<ServerCardUi> = emptyList(),
  val totalRamMb: Int = 0,
  val usedXmxMb: Int = 0,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repo: ServerRepository,
  private val controller: ServerController,
  private val installer: ServerInstaller,
) : ViewModel() {

  private val totalRam = RamUtils.totalRamMb(context)
  private val updates = MutableStateFlow<Map<Long, String>>(emptyMap())
  private val checked = mutableSetOf<Long>()
  private val messages = Channel<String>(Channel.BUFFERED)

  /** Error text from failed start (empty = generic). */
  val events = messages.receiveAsFlow()

  /** Server awaiting a "RAM above 65 %" confirmation. */
  val pendingStart = MutableStateFlow<ServerEntity?>(null)

  val ui: StateFlow<DashboardUi> = combine(repo.observeAll(), controller.runtimes, updates) { servers, rts, ups ->
    val cards = servers.map { s -> ServerCardUi(s, rts[s.id] ?: RuntimeState(), ups[s.id]) }
    val used = cards.filter { it.runtime.state == ServerState.RUNNING || it.runtime.state == ServerState.STARTING }
      .sumOf { it.server.xmxMb }
    DashboardUi(true, cards, totalRam, used)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUi(totalRamMb = totalRam))

  init {
    viewModelScope.launch {
      repo.observeAll().collect { list ->
        list.filter { it.launchSpec != null && checked.add(it.id) }.forEach { s ->
          launch(Dispatchers.IO) {
            val v = runCatching { installer.updateAvailable(s) }.getOrNull()
            if (v != null) updates.update { it + (s.id to v) }
          }
        }
      }
    }
  }

  fun onStart(server: ServerEntity) {
    val used = ui.value.usedXmxMb
    if (totalRam > 0 && used + server.xmxMb > totalRam * 0.65) pendingStart.value = server else start(server.id)
  }

  fun confirmStart() {
    val s = pendingStart.value ?: return
    pendingStart.value = null
    start(s.id)
  }

  fun dismissStart() {
    pendingStart.value = null
  }

  private fun start(id: Long) {
    viewModelScope.launch {
      val r = withContext(Dispatchers.Default) { controller.start(id) }
      r.exceptionOrNull()?.let { messages.trySend(it.message.orEmpty()) }
    }
  }

  fun onStop(id: Long) {
    viewModelScope.launch { controller.stop(id) }
  }
}
