package com.mcmanager.feature.versions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.engine.ServerInstaller
import com.mcmanager.core.engine.VersionRepository
import com.mcmanager.core.model.ServerState
import com.mcmanager.feature.backups.BackupManager
import com.mcmanager.feature.plugins.Compat
import com.mcmanager.feature.plugins.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingSwitch(
  val mcVersion: String,
  val build: String?,
  val downgrade: Boolean,
  /** Same MC version, newer build. */
  val isUpdate: Boolean,
)

data class VersionsUiState(
  val server: ServerEntity? = null,
  val runtime: ServerState = ServerState.STOPPED,
  val updateLabel: String? = null,
  val checkingUpdate: Boolean = true,
  val versions: List<String> = emptyList(),
  val versionsLoading: Boolean = true,
  val versionsError: String? = null,
  val search: String = "",
  val history: List<HistoryEntry> = emptyList(),
  val pendingSwitch: PendingSwitch? = null,
  val pendingRollback: HistoryEntry? = null,
  val busy: Boolean = false,
  val busyStep: String? = null,
  val busyFraction: Float? = null,
) {
  /** Changing the jar needs the process to be down. */
  val canChange: Boolean get() = runtime == ServerState.STOPPED || runtime == ServerState.CRASHED
  val filtered: List<String>
    get() = if (search.isBlank()) versions else versions.filter { it.contains(search.trim(), ignoreCase = true) }
}

@HiltViewModel
class VersionsViewModel @Inject constructor(
  savedState: SavedStateHandle,
  private val servers: ServerRepository,
  private val controller: ServerController,
  private val installer: ServerInstaller,
  private val versionRepo: VersionRepository,
  private val backups: BackupManager,
  private val store: VersionHistoryStore,
) : ViewModel() {

  private val serverId: Long = savedState.get<Long>("id") ?: savedState.get<String>("id")?.toLongOrNull() ?: 0L

  private val _state = MutableStateFlow(VersionsUiState())
  val state: StateFlow<VersionsUiState> = _state.asStateFlow()

  private val _messages = Channel<UiText>(Channel.BUFFERED)
  val messages: Flow<UiText> = _messages.receiveAsFlow()

  private var loaded = false

  init {
    viewModelScope.launch {
      servers.observe(serverId).filterNotNull().collect { s ->
        _state.update { it.copy(server = s) }
        reloadHistory(s)
        if (!loaded) {
          loaded = true
          reload()
        }
      }
    }
    viewModelScope.launch {
      controller.runtime(serverId).collect { r -> _state.update { it.copy(runtime = r.state) } }
    }
  }

  private fun say(res: Int, vararg args: Any) {
    _messages.trySend(UiText(res, args.toList()))
  }

  private fun sayError(t: Throwable) {
    _messages.trySend(UiText(R.string.versions_error, listOf(t.message ?: t.javaClass.simpleName)))
  }

  private suspend fun reloadHistory(s: ServerEntity) {
    val list = withContext(Dispatchers.IO) { store.read(File(s.dir)) }
    _state.update { it.copy(history = list.sortedByDescending { e -> e.date }) }
  }

  fun reload() {
    val s = _state.value.server ?: return
    viewModelScope.launch {
      _state.update { it.copy(checkingUpdate = true, versionsLoading = true, versionsError = null) }
      val upd = runCatching { installer.updateAvailable(s) }.getOrNull()
      _state.update { it.copy(updateLabel = upd, checkingUpdate = false) }
      try {
        val list = versionRepo.mcVersions(s.engine)
        _state.update { it.copy(versions = list, versionsLoading = false) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _state.update { it.copy(versionsLoading = false, versionsError = e.message ?: e.javaClass.simpleName) }
      }
    }
  }

  fun setSearch(q: String) = _state.update { it.copy(search = q) }

  // ---- switch / update -------------------------------------------------------------------

  fun requestSwitch(mc: String) {
    val s = _state.value.server ?: return
    if (!_state.value.canChange || mc == s.mcVersion) return
    viewModelScope.launch {
      val build = runCatching { versionRepo.latestBuild(s.engine, mc) }.getOrNull()
      val cur = Compat.parseVersion(s.mcVersion)
      val tgt = Compat.parseVersion(mc)
      val down = cur != null && tgt != null && Compat.compare(tgt, cur) < 0
      _state.update { it.copy(pendingSwitch = PendingSwitch(mc, build, down, isUpdate = false)) }
    }
  }

  fun requestUpdate() {
    val s = _state.value.server ?: return
    if (!_state.value.canChange) return
    viewModelScope.launch {
      val build = runCatching { versionRepo.latestBuild(s.engine, s.mcVersion) }.getOrNull() ?: _state.value.updateLabel
      _state.update { it.copy(pendingSwitch = PendingSwitch(s.mcVersion, build, downgrade = false, isUpdate = true)) }
    }
  }

  fun cancelSwitch() = _state.update { it.copy(pendingSwitch = null) }

  fun confirmSwitch(backupFirst: Boolean) {
    val p = _state.value.pendingSwitch ?: return
    _state.update { it.copy(pendingSwitch = null) }
    viewModelScope.launch { performSwitch(p, backupFirst) }
  }

  private fun busy(step: String?, fraction: Float? = null) =
    _state.update { it.copy(busy = true, busyStep = step, busyFraction = fraction) }

  private fun idle() = _state.update { it.copy(busy = false, busyStep = null, busyFraction = null) }

  private suspend fun performSwitch(p: PendingSwitch, backupFirst: Boolean) {
    val server = servers.get(serverId) ?: return
    if (!_state.value.canChange) {
      say(R.string.versions_must_be_stopped)
      return
    }
    val dir = File(server.dir)
    var snapshot: HistoryEntry? = null
    try {
      busy(null)
      var backupId: Long? = null
      var backupLabel: String? = null
      if (backupFirst) {
        backupLabel = "Before switch to ${p.mcVersion}"
        busy("backup")
        backupId = backups.createBackup(serverId, backupLabel).getOrThrow().id
      }
      busy("save")
      val entry = withContext(Dispatchers.IO) {
        val e = store.snapshot(server, backupId, backupLabel)
        store.write(dir, store.read(dir) + e)
        e
      }
      snapshot = entry
      val target = server.copy(mcVersion = p.mcVersion, engineBuild = p.build)
      busy("install")
      val spec = installer.install(target, p.build) { prog ->
        _state.update { it.copy(busy = true, busyStep = prog.step, busyFraction = prog.fraction) }
      }
      val java = runCatching { versionRepo.requiredJava(p.mcVersion) }.getOrDefault(server.javaMajor)
      servers.update(target.copy(launchSpec = spec, javaMajor = java))
      say(if (p.isUpdate) R.string.versions_updated_ok else R.string.versions_switched_ok, p.mcVersion)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Put the old jar back so the server still starts.
      snapshot?.let { s -> runCatching { withContext(Dispatchers.IO) { store.restoreJar(server, s) } } }
      sayError(e)
    } finally {
      idle()
      servers.get(serverId)?.let { reloadHistory(it) }
      reload()
    }
  }

  // ---- rollback --------------------------------------------------------------------------

  fun requestRollback(e: HistoryEntry) {
    if (!_state.value.canChange) return
    _state.update { it.copy(pendingRollback = e) }
  }

  fun cancelRollback() = _state.update { it.copy(pendingRollback = null) }

  fun confirmRollback(restoreBackup: Boolean) {
    val entry = _state.value.pendingRollback ?: return
    _state.update { it.copy(pendingRollback = null) }
    viewModelScope.launch { performRollback(entry, restoreBackup && entry.backupId != null) }
  }

  private suspend fun performRollback(entry: HistoryEntry, restoreBackup: Boolean) {
    var server = servers.get(serverId) ?: return
    if (!_state.value.canChange) {
      say(R.string.versions_must_be_stopped)
      return
    }
    val dir = File(server.dir)
    try {
      busy(null)
      // A backup restore may replace .mcmanager too, so keep the history in memory and rewrite it after.
      var history = withContext(Dispatchers.IO) { store.read(dir) }
      if (restoreBackup) {
        busy("restore")
        backups.restore(entry.backupId!!).getOrThrow()
        server = servers.get(serverId) ?: server
      } else {
        // Snapshot where we are now so the rollback can itself be undone.
        val cur = withContext(Dispatchers.IO) { store.snapshot(server, null, null) }
        history = history + cur
      }
      busy("install")
      val ok = withContext(Dispatchers.IO) { store.restoreJar(server, entry) }
      if (!ok) throw IllegalStateException("The saved jar for ${entry.mcVersion} is missing")
      servers.update(
        server.copy(
          mcVersion = entry.mcVersion,
          engineBuild = entry.build,
          launchSpec = entry.launchSpec,
          javaMajor = entry.javaMajor,
        ),
      )
      withContext(Dispatchers.IO) {
        val rest = history.filter { it.id != entry.id }
        store.write(dir, rest)
        store.deleteSaved(server, entry, rest)
      }
      say(R.string.versions_rolled_back, entry.mcVersion)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      sayError(e)
    } finally {
      idle()
      servers.get(serverId)?.let { reloadHistory(it) }
      reload()
    }
  }
}
