package com.mcmanager.feature.server

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.nav.Routes
import com.mcmanager.core.util.RamUtils
import com.mcmanager.feature.backups.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerForm(
  val name: String,
  val port: String,
  val xmx: Int,
  val xms: Int,
  val jvmPreset: String,
  val gc: String,
  val extraArgs: String,
  val customJava: Boolean,
  val javaMajor: Int,
  val javaPath: String,
  val autoRestart: Boolean,
  val eula: Boolean,
  val backupHours: Int,
  val backupKeep: Int,
) {
  val portNumber: Int? get() = port.toIntOrNull()?.takeIf { it in 1024..65535 }
  val valid: Boolean get() = name.isNotBlank() && portNumber != null && (!customJava || javaPath.isNotBlank())
}

data class ServerSettingsUi(
  val loaded: Boolean = false,
  val missing: Boolean = false,
  val form: ServerForm? = null,
  val totalRamMb: Int = 0,
  val otherRunningXmxMb: Int = 0,
  val savedTick: Int = 0,
  val deleted: Boolean = false,
)

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  handle: SavedStateHandle,
  private val repo: ServerRepository,
  private val controller: ServerController,
  private val backupScheduler: BackupScheduler,
) : ViewModel() {

  private val id: Long = handle.get<Long>(Routes.ARG_ID) ?: 0L
  private val _ui = MutableStateFlow(ServerSettingsUi())
  val ui: StateFlow<ServerSettingsUi> = _ui.asStateFlow()

  val state: StateFlow<ServerState> = controller.runtime(id).map { rt: RuntimeState -> rt.state }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerState.STOPPED)

  init {
    viewModelScope.launch {
      val s = repo.get(id)
      if (s == null) {
        _ui.update { it.copy(loaded = true, missing = true) }
        return@launch
      }
      val total = withContext(Dispatchers.IO) { RamUtils.totalRamMb(context) }
      val rts = controller.runtimes.value
      val others = repo.getAll().filter {
        it.id != id && (rts[it.id]?.state == ServerState.RUNNING || rts[it.id]?.state == ServerState.STARTING)
      }.sumOf { it.xmxMb }
      _ui.update { it.copy(loaded = true, form = s.toForm(), totalRamMb = total, otherRunningXmxMb = others) }
    }
  }

  fun edit(transform: (ServerForm) -> ServerForm) {
    _ui.update { u -> u.form?.let { u.copy(form = transform(it)) } ?: u }
  }

  fun setXmx(v: Int) = edit { it.copy(xmx = v, xms = minOf(it.xms, v)) }

  fun setXms(v: Int) = edit { it.copy(xms = minOf(v, it.xmx)) }

  fun save() {
    val f = _ui.value.form ?: return
    if (!f.valid) return
    viewModelScope.launch {
      val current = repo.get(id) ?: return@launch
      val canChangePort = state.value == ServerState.STOPPED || state.value == ServerState.CRASHED
      val updated = current.copy(
        name = f.name.trim(),
        port = if (canChangePort) f.portNumber ?: current.port else current.port,
        xmxMb = f.xmx,
        xmsMb = f.xms,
        jvmPreset = f.jvmPreset,
        gc = f.gc,
        extraJvmArgs = f.extraArgs.trim(),
        javaMajor = f.javaMajor,
        customJavaPath = if (f.customJava) f.javaPath.trim() else null,
        autoRestart = f.autoRestart,
        eulaAccepted = f.eula,
        backupIntervalHours = f.backupHours,
        backupKeep = f.backupKeep,
      )
      repo.update(updated)
      if (updated.backupIntervalHours > 0) backupScheduler.schedule(id, updated.backupIntervalHours) else backupScheduler.cancel(id)
      _ui.update { it.copy(savedTick = it.savedTick + 1, form = updated.toForm()) }
    }
  }

  fun delete(deleteFiles: Boolean) {
    viewModelScope.launch {
      backupScheduler.cancel(id)
      withContext(Dispatchers.IO) { repo.delete(id, deleteFiles) }
      _ui.update { it.copy(deleted = true) }
    }
  }

  private fun ServerEntity.toForm() = ServerForm(
    name = name,
    port = port.toString(),
    xmx = xmxMb,
    xms = xmsMb,
    jvmPreset = jvmPreset,
    gc = gc,
    extraArgs = extraJvmArgs,
    customJava = customJavaPath != null,
    javaMajor = javaMajor,
    javaPath = customJavaPath.orEmpty(),
    autoRestart = autoRestart,
    eula = eulaAccepted,
    backupHours = backupIntervalHours,
    backupKeep = backupKeep,
  )
}
