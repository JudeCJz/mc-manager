package com.mcmanager.feature.create

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.ServerInstaller
import com.mcmanager.core.engine.VersionRepository
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.util.RamUtils
import com.mcmanager.feature.backups.BackupScheduler
import com.mcmanager.feature.server.maxXmxFor
import com.mcmanager.feature.server.nextFreePort
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CreateStep { ENGINE, VERSION, DETAILS, EULA, INSTALL }

data class CreateUi(
  val step: CreateStep = CreateStep.ENGINE,
  val engine: Engine? = null,
  val versions: List<String> = emptyList(),
  val versionsLoading: Boolean = false,
  val versionsError: String? = null,
  val includeSnapshots: Boolean = false,
  val search: String = "",
  val version: String? = null,
  val name: String = "",
  val port: String = "25565",
  val xmx: Int = 3072,
  val xms: Int = 1024,
  val seed: String = "",
  val gamemode: String = "survival",
  val jvmPreset: String = "aikar",
  val eula: Boolean = false,
  val totalRamMb: Int = 0,
  val runningXmxMb: Int = 0,
  val existingPorts: List<Int> = emptyList(),
  val existingNames: List<String> = emptyList(),
  val installing: Boolean = false,
  val installStep: String = "",
  val installFraction: Float? = null,
  val installError: String? = null,
  val createdId: Long? = null,
  val doneId: Long? = null,
) {
  val portNumber: Int? get() = port.toIntOrNull()?.takeIf { it in 1024..65535 }
  val nameTaken: Boolean get() = existingNames.any { it.equals(name.trim(), ignoreCase = true) }
  val detailsValid: Boolean get() = name.isNotBlank() && !nameTaken && portNumber != null
}

@HiltViewModel
class CreateServerViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repo: ServerRepository,
  private val settingsRepo: SettingsRepository,
  private val versionRepo: VersionRepository,
  private val installer: ServerInstaller,
  private val backupScheduler: BackupScheduler,
  private val controller: com.mcmanager.core.engine.ServerController,
) : ViewModel() {

  private val _ui = MutableStateFlow(CreateUi())
  val ui: StateFlow<CreateUi> = _ui.asStateFlow()
  private var versionsJob: Job? = null
  private var gc = "G1"
  private var autoBackupHours = 0
  private var autoRestart = true

  init {
    viewModelScope.launch {
      val settings = settingsRepo.settings.first()
      val servers = repo.getAll()
      val total = withContext(Dispatchers.IO) { RamUtils.totalRamMb(context) }
      val rts = controller.runtimes.value
      val running = servers.filter {
        val st = rts[it.id]?.state
        st == ServerState.RUNNING || st == ServerState.STARTING
      }.sumOf { it.xmxMb }
      val max = maxXmxFor(total)
      val xmx = settings.defaultXmxMb.coerceIn(512, max)
      gc = settings.defaultGc
      autoBackupHours = settings.autoBackupHours
      autoRestart = settings.autoRestartOnCrash
      _ui.update {
        it.copy(
          xmx = xmx,
          xms = settings.defaultXmsMb.coerceIn(256, xmx),
          jvmPreset = settings.defaultJvmPreset,
          totalRamMb = total,
          runningXmxMb = running,
          existingPorts = servers.map { s -> s.port },
          existingNames = servers.map { s -> s.name },
          port = nextFreePort(servers.map { s -> s.port }).toString(),
        )
      }
    }
  }

  // ----- navigation -----

  /** Returns true if the wizard handled the back press (moved a step back). */
  fun back(): Boolean {
    val s = _ui.value
    if (s.installing) return true
    return when (s.step) {
      CreateStep.ENGINE -> false
      CreateStep.INSTALL -> true
      else -> {
        _ui.update { it.copy(step = CreateStep.entries[it.step.ordinal - 1]) }
        true
      }
    }
  }

  fun next() {
    val s = _ui.value
    when (s.step) {
      CreateStep.ENGINE -> if (s.engine != null) {
        _ui.update { it.copy(step = CreateStep.VERSION) }
        if (s.versions.isEmpty() && !s.versionsLoading) loadVersions()
      }
      CreateStep.VERSION -> if (s.version != null) _ui.update { it.copy(step = CreateStep.DETAILS) }
      CreateStep.DETAILS -> if (s.detailsValid) _ui.update { it.copy(step = CreateStep.EULA) }
      CreateStep.EULA -> if (s.eula) {
        _ui.update { it.copy(step = CreateStep.INSTALL) }
        runInstall()
      }
      CreateStep.INSTALL -> Unit
    }
  }

  // ----- step 1/2 -----

  fun selectEngine(e: Engine) {
    if (!e.supported) return
    if (_ui.value.engine == e) return
    _ui.update { it.copy(engine = e, version = null, versions = emptyList(), versionsError = null, includeSnapshots = false, search = "") }
  }

  fun loadVersions() {
    val s = _ui.value
    val engine = s.engine ?: return
    versionsJob?.cancel()
    _ui.update { it.copy(versionsLoading = true, versionsError = null) }
    versionsJob = viewModelScope.launch {
      try {
        val list = versionRepo.mcVersions(engine, s.includeSnapshots)
        _ui.update { it.copy(versions = list, versionsLoading = false, version = it.version?.takeIf { v -> v in list }) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _ui.update { it.copy(versionsLoading = false, versionsError = e.message.orEmpty()) }
      }
    }
  }

  fun setSnapshots(on: Boolean) {
    _ui.update { it.copy(includeSnapshots = on) }
    loadVersions()
  }

  fun setSearch(q: String) = _ui.update { it.copy(search = q) }

  fun selectVersion(v: String) = _ui.update { it.copy(version = v) }

  // ----- step 3 -----

  fun setName(v: String) = _ui.update { it.copy(name = v.take(40)) }

  fun setPort(v: String) = _ui.update { it.copy(port = v.filter { c -> c.isDigit() }.take(5)) }

  fun setXmx(v: Int) = _ui.update { it.copy(xmx = v, xms = minOf(it.xms, v)) }

  fun setXms(v: Int) = _ui.update { it.copy(xms = minOf(v, it.xmx)) }

  fun setSeed(v: String) = _ui.update { it.copy(seed = v) }

  fun setGamemode(v: String) = _ui.update { it.copy(gamemode = v) }

  fun setJvmPreset(v: String) = _ui.update { it.copy(jvmPreset = v) }

  fun setEula(v: Boolean) = _ui.update { it.copy(eula = v) }

  // ----- step 5 -----

  fun retryInstall() {
    if (!_ui.value.installing) runInstall()
  }

  fun deleteCreated() {
    val id = _ui.value.createdId
    viewModelScope.launch {
      if (id != null) withContext(Dispatchers.IO) { repo.delete(id, deleteFiles = true) }
      _ui.update { it.copy(createdId = null, installError = null, installing = false, step = CreateStep.DETAILS) }
    }
  }

  private fun runInstall() {
    viewModelScope.launch {
      _ui.update { it.copy(installing = true, installError = null, installStep = "", installFraction = null) }
      try {
        val st = _ui.value
        val engine = st.engine!!
        val version = st.version!!
        var id = st.createdId
        if (id == null) {
          val java = versionRepo.requiredJava(version)
          val build = versionRepo.latestBuild(engine, version)
          val hardcore = st.gamemode == "hardcore"
          val gm = if (hardcore) "survival" else st.gamemode
          val entity = ServerEntity(
            name = st.name.trim(),
            engine = engine,
            mcVersion = version,
            engineBuild = build,
            dir = "",
            port = st.portNumber ?: 25565,
            xmxMb = st.xmx,
            xmsMb = st.xms,
            jvmPreset = st.jvmPreset,
            gc = gc,
            javaMajor = java,
            eulaAccepted = true,
            autoRestart = autoRestart,
            backupIntervalHours = autoBackupHours,
            seed = st.seed.trim(),
            gamemode = gm,
          )
          id = withContext(Dispatchers.IO) { repo.create(entity) }
          _ui.update { it.copy(createdId = id) }
          settingsRepo.update { it.copy(eulaConfirmedOnce = true) }
          val created = repo.get(id)
          if (created != null) withContext(Dispatchers.IO) { writeProperties(created, hardcore) }
          if (autoBackupHours > 0) backupScheduler.schedule(id, autoBackupHours)
        }
        val server = repo.get(id) ?: error("Server entry vanished")
        val spec = installer.install(server, server.engineBuild) { p ->
          _ui.update { it.copy(installStep = p.step, installFraction = p.fraction) }
        }
        val fresh = repo.get(id) ?: server
        repo.update(fresh.copy(launchSpec = spec))
        _ui.update { it.copy(installing = false, doneId = id) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _ui.update { it.copy(installing = false, installError = e.message ?: e.javaClass.simpleName) }
      }
    }
  }

  /** Seeds server.properties so seed, game mode and port apply on first start (Minecraft fills in the rest). */
  private fun writeProperties(server: ServerEntity, hardcore: Boolean) {
    val file = File(server.dir, "server.properties")
    if (file.exists()) return
    val p = Properties()
    p.setProperty("server-port", server.port.toString())
    p.setProperty("gamemode", server.gamemode)
    if (hardcore) {
      p.setProperty("hardcore", "true")
      p.setProperty("difficulty", "hard")
    }
    if (server.seed.isNotBlank()) p.setProperty("level-seed", server.seed)
    File(server.dir).mkdirs()
    FileOutputStream(file).use { p.store(it, "Generated by MCManager") }
  }
}
