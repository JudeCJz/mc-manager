package com.mcmanager.feature.console

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.settings.AppSettings
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ConsoleLine
import com.mcmanager.core.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConsoleViewModel @Inject constructor(
  handle: SavedStateHandle,
  private val controller: ServerController,
  settingsRepo: SettingsRepository,
) : ViewModel() {

  private val id: Long = handle.get<Long>(Routes.ARG_ID) ?: 0L

  val settings: StateFlow<AppSettings> =
    settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

  /** Console lines, capped to the user's "max lines" setting. */
  val lines: StateFlow<List<ConsoleLine>> = combine(controller.console(id), settingsRepo.settings) { l, s ->
    if (l.size > s.consoleMaxLines) l.takeLast(s.consoleMaxLines) else l
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val input = MutableStateFlow("")
  private val history = ArrayDeque<String>()
  private var historyIndex = -1

  fun setInput(v: String) {
    input.value = v
  }

  fun send() {
    val cmd = input.value.trim()
    if (cmd.isEmpty()) return
    sendCommand(cmd)
    input.value = ""
  }

  fun sendCommand(cmd: String) {
    controller.sendCommand(id, cmd.trimStart('/'))
    if (history.lastOrNull() != cmd) {
      history.addLast(cmd)
      while (history.size > 50) history.removeFirst()
    }
    historyIndex = -1
  }

  fun historyUp() {
    if (history.isEmpty()) return
    historyIndex = if (historyIndex == -1) history.lastIndex else (historyIndex - 1).coerceAtLeast(0)
    input.value = history[historyIndex]
  }

  fun historyDown() {
    if (historyIndex == -1) return
    historyIndex++
    if (historyIndex > history.lastIndex) {
      historyIndex = -1
      input.value = ""
    } else {
      input.value = history[historyIndex]
    }
  }
}
