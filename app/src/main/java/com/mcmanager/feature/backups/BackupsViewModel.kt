package com.mcmanager.feature.backups

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.BackupDao
import com.mcmanager.core.data.db.BackupEntity
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.feature.network.UiMsg
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BackupsViewModel @Inject constructor(
  private val manager: BackupManager,
  private val scheduler: BackupScheduler,
  private val servers: ServerRepository,
  private val dao: BackupDao,
  private val controller: ServerController,
) : ViewModel() {

  private val idFlow = MutableStateFlow(-1L)

  val server: StateFlow<ServerEntity?> = idFlow.flatMapLatest { servers.observe(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  val backups: StateFlow<List<BackupEntity>> = idFlow.flatMapLatest { dao.observeFor(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  val runtime: StateFlow<RuntimeState> = idFlow.flatMapLatest { controller.runtime(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RuntimeState())

  /** null = idle; Pair(fraction or null=indeterminate). */
  val busy: StateFlow<Pair<Float?, Boolean>> = idFlow.flatMapLatest { id ->
    manager.progress.map { m -> if (m.containsKey(id)) Pair(m[id], true) else Pair(null, false) }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Pair(null, false))

  private val _messages = MutableSharedFlow<UiMsg>(extraBufferCapacity = 8)
  val messages: SharedFlow<UiMsg> = _messages

  fun bind(serverId: Long) {
    idFlow.value = serverId
  }

  private val id get() = idFlow.value

  private fun report(r: Result<*>, ok: Int) {
    _messages.tryEmit(if (r.isSuccess) UiMsg(ok) else UiMsg(R.string.ops_error_fmt, r.exceptionOrNull()?.message))
  }

  fun create(label: String) = viewModelScope.launch { report(manager.createBackup(id, label, false), R.string.ops_backup_created) }
  fun restore(b: BackupEntity) = viewModelScope.launch { report(manager.restore(b.id), R.string.ops_backup_restored) }
  fun delete(b: BackupEntity) = viewModelScope.launch { manager.delete(b.id) }
  fun export(b: BackupEntity, dest: Uri) = viewModelScope.launch { report(manager.export(b.id, dest), R.string.ops_backup_exported) }
  fun import(src: Uri) = viewModelScope.launch { report(manager.import(id, src), R.string.ops_backup_imported) }

  fun setSchedule(hours: Int, keep: Int) {
    val s = server.value ?: return
    viewModelScope.launch {
      servers.update(s.copy(backupIntervalHours = hours, backupKeep = keep.coerceIn(1, 100)))
      if (hours > 0) scheduler.schedule(s.id, hours) else scheduler.cancel(s.id)
      if (keep != s.backupKeep) manager.applyRetention(s.id)
    }
  }
}
