package com.mcmanager.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.JavaRuntimeManager
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ConsoleLine
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.core.model.ServerState
import com.mcmanager.engine.JavaEnv
import com.mcmanager.engine.JvmFlags
import com.mcmanager.engine.JvmPresetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

@Singleton
class ServerControllerImpl @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repo: ServerRepository,
  private val settingsRepo: SettingsRepository,
  private val javaManager: JavaRuntimeManager,
  private val presets: JvmPresetRepository,
) : ServerController {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val sessions = ConcurrentHashMap<Long, Session>()
  private val lock = Any()
  private val _runtimes = MutableStateFlow<Map<Long, RuntimeState>>(emptyMap())
  private val _pendingRestarts = MutableStateFlow(0)

  @Volatile private var consoleCap = 5000

  override val runtimes: StateFlow<Map<Long, RuntimeState>> = _runtimes.asStateFlow()

  /** Number of auto-restarts scheduled but not yet started; the service stays alive while > 0. */
  val pendingRestarts: StateFlow<Int> = _pendingRestarts.asStateFlow()

  init {
    scope.launch { settingsRepo.settings.collect { consoleCap = it.consoleMaxLines } }
  }

  private inner class Session(val id: Long) {
    val state = MutableStateFlow(RuntimeState())
    val console = ConsoleBuffer(scope) { consoleCap }
    val startMutex = Mutex()

    @Volatile var process: ServerProcess? = null

    @Volatile var startJob: Deferred<*>? = null

    @Volatile var restartJob: Job? = null
    var statsJob: Job? = null
    var pollJob: Job? = null
    val crashTimes = ArrayList<Long>()
    @Volatile var lastError: String? = null
  }

  private fun session(id: Long): Session = sessions.getOrPut(id) { Session(id) }

  override fun runtime(id: Long): StateFlow<RuntimeState> = session(id).state.asStateFlow()

  override fun console(id: Long): StateFlow<List<ConsoleLine>> = session(id).console.flow

  private fun mutate(s: Session, f: (RuntimeState) -> RuntimeState) {
    synchronized(lock) {
      val new = f(s.state.value)
      s.state.value = new
      _runtimes.value = _runtimes.value + (s.id to new)
    }
  }

  private fun sys(s: Session, text: String) = s.console.add(text, ConsoleLine.Kind.SYSTEM)

  // ---------------------------------------------------------------- start

  override suspend fun start(id: Long): Result<Unit> {
    val s = session(id)
    val job = scope.async { doStart(s, auto = false) }
    s.startJob = job
    return job.await()
  }

  private fun fail(msg: String): Result<Unit> = Result.failure(IllegalStateException(msg))

  private suspend fun doStart(s: Session, auto: Boolean): Result<Unit> {
    if (!s.startMutex.tryLock()) return fail(context.getString(R.string.eng_err_running))
    try {
      if (s.state.value.state.isActive()) return fail(context.getString(R.string.eng_err_running))
      if (!auto) cancelRestart(s)
      val server = repo.get(s.id) ?: return fail(context.getString(R.string.eng_err_server_missing))
      if (server.launchSpec.isNullOrBlank()) return fail(context.getString(R.string.eng_err_not_installed))
      if (!server.eulaAccepted) return fail(context.getString(R.string.eng_err_eula))

      mutate(s) {
        RuntimeState(state = ServerState.INSTALLING, message = context.getString(R.string.eng_msg_preparing))
      }
      ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))

      return try {
        launchProcess(s, server)
        Result.success(Unit)
      } catch (e: CancellationException) {
        mutate(s) { RuntimeState() }
        throw e
      } catch (e: Exception) {
        val msg = context.getString(R.string.eng_err_start_failed, e.message ?: e.javaClass.simpleName)
        sys(s, msg)
        mutate(s) { RuntimeState(state = ServerState.CRASHED, message = msg) }
        Result.failure(e)
      }
    } finally {
      s.startMutex.unlock()
    }
  }

  private suspend fun launchProcess(s: Session, server: ServerEntity) {
    // Java (may download a runtime).
    val javaBin = javaManager.javaFor(server) { p ->
      mutate(s) { it.copy(state = ServerState.INSTALLING, message = p.step, progress = p.fraction) }
    }
    val dir = File(server.dir)
    val javaMajor = Regex("jre(\\d+)").find(JavaEnv.homeOf(javaBin).name)?.groupValues?.get(1)?.toIntOrNull()
      ?: server.javaMajor

    val args = withContext(Dispatchers.IO) {
      dir.mkdirs()
      File(dir, "tmp").mkdirs()
      writeEula(dir)
      ensureProperties(dir, server)
      val flags = JvmFlags.build(server.copy(javaMajor = javaMajor), presets.byId(server.jvmPreset))
      val spec = server.launchSpec!!
      flags + (if (spec.startsWith("@")) listOf(spec) else listOf("-jar", spec)) + "nogui"
    }
    val command = listOf(javaBin.absolutePath) + args
    sys(s, context.getString(R.string.eng_sys_starting, javaMajor, command.joinToString(" ")))

    val pb = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
    JavaEnv.apply(pb, JavaEnv.homeOf(javaBin), context)
    val process = withContext(Dispatchers.IO) { pb.start() }

    s.lastError = null
    val sp = ServerProcess(
      process, scope, javaBin,
      onLine = { line -> onLine(s, server, line) },
      onExit = { code, requested -> onExit(s, server, code, requested) },
    )
    s.process = sp
    mutate(s) {
      RuntimeState(state = ServerState.STARTING, pid = sp.pid, startedAt = System.currentTimeMillis())
    }
    sp.pid?.let { pid ->
      s.statsJob = scope.launch {
        StatsSampler.run(pid) { cpu, rss -> mutate(s) { it.copy(cpuPercent = cpu, rssMb = rss) } }
      }
    }
    runCatching { repo.get(server.id)?.let { repo.update(it.copy(lastStartedAt = System.currentTimeMillis())) } }
  }

  private fun writeEula(dir: File) {
    File(dir, "eula.txt").writeText(
      "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n" +
        "eula=true\n",
    )
  }

  private fun ensureProperties(dir: File, server: ServerEntity) {
    val f = File(dir, "server.properties")
    if (!f.isFile) {
      f.writeText(
        buildString {
          append("server-port=${server.port}\n")
          append("level-seed=${server.seed}\n")
          append("gamemode=${server.gamemode}\n")
          append("motd=${server.name.replace('\n', ' ')}\n")
        },
      )
      return
    }
    val lines = f.readLines()
    var found = false
    val out = lines.map { l ->
      if (l.trimStart().startsWith("server-port=") || l.trimStart().startsWith("server-port ")) {
        found = true
        "server-port=${server.port}"
      } else {
        l
      }
    }.toMutableList()
    if (!found) out += "server-port=${server.port}"
    if (out != lines) f.writeText(out.joinToString("\n") + "\n")
  }

  // ---------------------------------------------------------------- output / exit

  private fun onLine(s: Session, server: ServerEntity, raw: String) {
    val text = LogParser.stripAnsi(raw).trimEnd('\r')
    val kind = if (LogParser.isErrorLevel(text)) ConsoleLine.Kind.ERR else ConsoleLine.Kind.OUT
    s.console.add(text, kind)
    if (LogParser.isCrashReason(text)) s.lastError = text.trim().take(200)
    for (e in LogParser.parse(text)) {
      when (e) {
        LogEvent.Done -> onDone(s, server)
        is LogEvent.PlayerJoined -> mutate(s) { it.copy(onlinePlayers = (it.onlinePlayers + e.name).distinct()) }
        is LogEvent.PlayerLeft -> mutate(s) { it.copy(onlinePlayers = it.onlinePlayers - e.name) }
        is LogEvent.PlayerList -> mutate(s) { it.copy(onlinePlayers = e.names, maxPlayers = e.max) }
        is LogEvent.Tps -> mutate(s) { it.copy(tps = e.oneMinute) }
      }
    }
  }

  private fun onDone(s: Session, server: ServerEntity) {
    var changed = false
    mutate(s) {
      if (it.state == ServerState.STARTING) {
        changed = true
        it.copy(state = ServerState.RUNNING, message = null, progress = null)
      } else {
        it
      }
    }
    if (!changed) return
    s.pollJob?.cancel()
    s.pollJob = scope.launch {
      s.process?.writeLine("list")
      if (server.engine == Engine.PAPER || server.engine == Engine.PURPUR) {
        delay(5_000)
        while (isActive) {
          s.process?.writeLine("tps")
          delay(30_000)
        }
      }
    }
  }

  private fun onExit(s: Session, server: ServerEntity, code: Int, requested: Boolean) {
    s.statsJob?.cancel()
    s.pollJob?.cancel()
    s.process = null
    sys(s, context.getString(R.string.eng_sys_exited, code))
    val clean = requested || code == 0
    if (clean) {
      mutate(s) { RuntimeState(state = ServerState.STOPPED, lastExitCode = code) }
      return
    }
    val reason = s.lastError
    val msg = if (reason != null) context.getString(R.string.eng_msg_crash_code_detail, code, reason)
    else context.getString(R.string.eng_msg_crash_code, code)
    mutate(s) { RuntimeState(state = ServerState.CRASHED, lastExitCode = code, message = msg) }
    scheduleAutoRestart(s)
  }

  private fun scheduleAutoRestart(s: Session) {
    scope.launch {
      val server = repo.get(s.id) ?: return@launch
      val settings = settingsRepo.settings.first()
      if (!server.autoRestart || !settings.autoRestartOnCrash) return@launch
      val now = System.currentTimeMillis()
      val attempt: Int
      synchronized(s.crashTimes) {
        s.crashTimes.removeAll { now - it > RESTART_WINDOW_MS }
        if (s.crashTimes.size >= MAX_RESTARTS) {
          attempt = -1
        } else {
          s.crashTimes += now
          attempt = s.crashTimes.size
        }
      }
      if (attempt < 0) {
        sys(s, context.getString(R.string.eng_sys_autorestart_giveup))
        mutate(s) { it.copy(message = context.getString(R.string.eng_sys_autorestart_giveup)) }
        return@launch
      }
      sys(s, context.getString(R.string.eng_sys_autorestart, (RESTART_DELAY_MS / 1000).toInt(), attempt, MAX_RESTARTS))
      _pendingRestarts.value += 1
      val job = launch {
        try {
          delay(RESTART_DELAY_MS)
          if (s.state.value.state == ServerState.CRASHED) doStart(s, auto = true)
        } finally {
          _pendingRestarts.value -= 1
        }
      }
      s.restartJob = job
    }
  }

  private fun cancelRestart(s: Session) {
    s.restartJob?.cancel()
    s.restartJob = null
  }

  // ---------------------------------------------------------------- stop / kill / commands

  override suspend fun stop(id: Long) {
    val s = session(id)
    cancelRestart(s)
    val p = s.process
    if (p == null) {
      when (s.state.value.state) {
        ServerState.INSTALLING -> {
          s.startJob?.cancel()
          mutate(s) { RuntimeState() }
        }
        ServerState.CRASHED -> mutate(s) { it.copy(state = ServerState.STOPPED) }
        else -> Unit
      }
      return
    }
    mutate(s) { if (it.state == ServerState.STOPPED) it else it.copy(state = ServerState.STOPPING, message = null) }
    sys(s, context.getString(R.string.eng_sys_stopping))
    p.stopGracefully { sys(s, context.getString(R.string.eng_sys_force)) }
  }

  override suspend fun restart(id: Long) {
    stop(id)
    val s = session(id)
    // Wait for the state to settle (stop() returns after exit, but be safe).
    var waited = 0
    while (s.state.value.state.isActive() && waited < 100) {
      delay(100)
      waited++
    }
    start(id)
  }

  override fun kill(id: Long) {
    val s = session(id)
    cancelRestart(s)
    val p = s.process
    if (p != null) {
      mutate(s) { it.copy(state = ServerState.STOPPING, message = null) }
      p.kill()
    } else if (s.state.value.state == ServerState.INSTALLING) {
      // Cancels the Java download; the start coroutine resets the state.
      s.startJob?.cancel()
      mutate(s) { RuntimeState() }
    }
  }

  override fun sendCommand(id: Long, command: String) {
    val s = session(id)
    val cmd = command.trim().removePrefix("/").trim()
    if (cmd.isEmpty()) return
    val p = s.process
    if (p == null) {
      sys(s, context.getString(R.string.eng_sys_not_running))
      return
    }
    s.console.add(cmd, ConsoleLine.Kind.CMD)
    scope.launch { p.writeLine(cmd) }
  }

  override suspend fun stopAll() {
    val active = sessions.values.filter { it.state.value.state.isActive() }
    coroutineScope { active.map { async { stop(it.id) } }.awaitAll() }
  }

  companion object {
    private const val RESTART_DELAY_MS = 5_000L
    private const val RESTART_WINDOW_MS = 10 * 60_000L
    private const val MAX_RESTARTS = 3
  }
}
