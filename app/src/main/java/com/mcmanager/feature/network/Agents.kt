package com.mcmanager.feature.network

import android.content.Context
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.net.download
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

enum class AgentPhase { NOT_INSTALLED, DOWNLOADING, NEEDS_CLAIM, STARTING, RUNNING, STOPPED, ERROR }

data class AgentState(
  val phase: AgentPhase = AgentPhase.STOPPED,
  val message: String? = null,
  val progress: Float? = null,
  val claimUrl: String? = null,
  val addresses: List<String> = emptyList(),
  val log: List<String> = emptyList(),
  val installed: Boolean = false,
)

/** Pure line parsers for the tunnel agents (unit-tested). */
object AgentParsers {
  private val PLAYIT_ADDR = Regex(
    "([a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:joinmc\\.link|playit\\.gg|ply\\.gg|playit\\.plus)(?::\\d{2,5})?)",
    RegexOption.IGNORE_CASE,
  )
  private val NGROK_URL = Regex("\"url\":\"tcp://([^\"]+)\"")
  private val NGROK_ERR = Regex("\"err\":\"([^\"]+)\"")
  private val CLAIM_URL = Regex("https://playit\\.gg/claim/[A-Za-z0-9?=&_.-]+")

  fun playitAddress(line: String): String? = PLAYIT_ADDR.find(line)?.value?.lowercase()
  fun playitClaimUrl(line: String): String? = CLAIM_URL.find(line)?.value
  fun ngrokTcpUrl(line: String): String? = NGROK_URL.find(line)?.groupValues?.get(1)
  fun ngrokError(line: String): String? =
    if (line.contains("\"lvl\":\"eror\"") || line.contains("\"lvl\":\"crit\"") || line.contains("\"lvl\":\"error\""))
      NGROK_ERR.find(line)?.groupValues?.get(1) else null

  /** Secret printed by `playit-cli claim exchange`: the last whitespace-free token of >= 32 chars. */
  fun playitSecret(output: String): String? =
    output.lines().map { it.trim() }.lastOrNull { it.length >= 32 && it.none { c -> c.isWhitespace() } }
}

abstract class AgentBase(protected val context: Context) {
  protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  protected val _state = MutableStateFlow(AgentState())
  val state: StateFlow<AgentState> get() = _state
  protected val binDir get() = File(context.filesDir, "bin").apply { mkdirs() }

  protected fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(60)) }
  protected fun isElf(f: File): Boolean = try {
    f.inputStream().use { i -> val b = ByteArray(4); i.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() }
  } catch (_: Exception) { false }
}

/**
 * playit.gg agent. UNTESTED on a device: derived from the playit-agent v1.2 sources/README.
 * Uses `playit-linux-aarch64` (the `playitd` daemon, static musl) and `playit-cli-linux-aarch64`
 * (used only for `claim exchange`).
 */
@Singleton
class PlayitAgent @Inject constructor(
  @ApplicationContext context: Context,
  private val http: OkHttpClient,
  private val settings: SettingsRepository,
) {
  private val impl = PlayitImpl(context, http, settings)
  val state: StateFlow<AgentState> get() = impl.state
  fun start() = impl.enable()
  fun stop() = impl.disable()
  fun resetSecret() = impl.resetSecret()
  fun saveSecret(secret: String) = impl.saveSecret(secret)
  fun hasSecret(): Boolean = impl.hasSecret()
  fun isRunning() = impl.state.value.phase.let { it == AgentPhase.RUNNING || it == AgentPhase.STARTING }
  fun refreshInstalled() = impl.refreshInstalled()
}

private const val PLAYIT_BASE = "https://github.com/playit-cloud/playit-agent/releases/latest/download/"

internal class PlayitImpl(
  context: Context,
  private val http: OkHttpClient,
  private val settings: SettingsRepository,
) : AgentBase(context) {
  private val dir get() = File(context.filesDir, "playit").apply { mkdirs() }
  private val secretFile get() = File(dir, "secret.txt")
  private var proc: SupervisedProcess? = null
  private var job: Job? = null
  @Volatile private var claimProc: Process? = null

  init { refreshInstalled() }

  fun refreshInstalled() = _state.update { it.copy(installed = File(binDir, "playitd").canExecute()) }

  private fun env(pb: ProcessBuilder) = pb.apply {
    directory(dir)
    environment()["HOME"] = dir.absolutePath
    environment()["TMPDIR"] = context.cacheDir.absolutePath
    environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
  }

  private suspend fun ensureBin(name: String, asset: String): File {
    val f = File(binDir, name)
    if (f.canExecute() && f.length() > 500_000 && isElf(f)) return f
    _state.update { it.copy(phase = AgentPhase.DOWNLOADING, message = name, progress = null) }
    http.download(PLAYIT_BASE + asset, f) { p -> _state.update { s -> s.copy(progress = p) } }
    if (!isElf(f)) { f.delete(); throw IOException("Downloaded file for $asset is not an executable") }
    f.setExecutable(true, false)
    return f
  }

  fun enable() {
    if (job?.isActive == true) return
    job = scope.launch {
      try {
        settings.update { it.copy(playitEnabled = true) }
        _state.update { it.copy(message = null, addresses = emptyList()) }
        val daemon = ensureBin("playitd", "playit-linux-aarch64")
        if (!secretFile.isFile || secretFile.length() < 8) {
          val cli = ensureBin("playit-cli", "playit-cli-linux-aarch64")
          claim(cli)
        }
        refreshInstalled()
        runDaemon(daemon)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(phase = AgentPhase.ERROR, message = e.message ?: e.toString()) }
      }
    }
  }

  private fun claim(cli: File) {
    val code = ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val url = "https://playit.gg/claim/$code"
    _state.update { it.copy(phase = AgentPhase.NEEDS_CLAIM, claimUrl = url, message = null) }
    val pb = env(ProcessBuilder(cli.absolutePath, "claim", "exchange", code, "--wait", "0")).redirectErrorStream(true)
    val p = pb.start()
    claimProc = p
    val out = p.inputStream.bufferedReader().use { it.readText() }
    p.waitFor()
    claimProc = null
    val secret = AgentParsers.playitSecret(out) ?: throw IOException("Claim failed: ${out.lines().lastOrNull { it.isNotBlank() }.orEmpty()}")
    secretFile.writeText(secret)
    secretFile.setReadable(false, false); secretFile.setReadable(true, true)
    _state.update { it.copy(claimUrl = null) }
  }

  private fun runDaemon(daemon: File) {
    val sp = SupervisedProcess(
      builder = {
        env(
          ProcessBuilder(
            daemon.absolutePath, "--secret-path", secretFile.absolutePath,
            "--socket-path", File(dir, "playitd.sock").absolutePath,
          ),
        )
      },
      onLine = { line ->
        log(line)
        AgentParsers.playitAddress(line)?.let { a ->
          _state.update { s -> if (a in s.addresses) s else s.copy(addresses = s.addresses + a) }
        }
      },
      onPhase = { ph, msg ->
        _state.update {
          when (ph) {
            SupervisedProcess.Phase.STARTING -> it.copy(phase = AgentPhase.STARTING, message = null)
            SupervisedProcess.Phase.RUNNING -> it.copy(phase = AgentPhase.RUNNING, message = null)
            SupervisedProcess.Phase.BACKOFF -> it.copy(phase = AgentPhase.STARTING, message = msg)
            SupervisedProcess.Phase.STOPPED -> it.copy(phase = AgentPhase.STOPPED, message = null)
            SupervisedProcess.Phase.FAILED_TO_START -> it.copy(phase = AgentPhase.ERROR, message = msg)
          }
        }
      },
    )
    proc = sp
    sp.start()
  }

  fun disable() {
    scope.launch { settings.update { it.copy(playitEnabled = false) } }
    claimProc?.destroy()
    job?.cancel(); job = null
    proc?.stop(); proc = null
    _state.update { it.copy(phase = AgentPhase.STOPPED, claimUrl = null, message = null, addresses = emptyList()) }
  }

  fun resetSecret() {
    disable()
    secretFile.delete()
  }

  fun saveSecret(secret: String) {
    val clean = secret.trim()
    if (clean.isNotBlank()) {
      secretFile.writeText(clean)
      secretFile.setReadable(false, false); secretFile.setReadable(true, true)
      _state.update { it.copy(claimUrl = null, message = null) }
    }
  }

  fun hasSecret(): Boolean = secretFile.isFile && secretFile.length() >= 8
}

@Singleton
class NgrokAgent @Inject constructor(
  @ApplicationContext context: Context,
  private val http: OkHttpClient,
  private val settings: SettingsRepository,
) : AgentBase(context) {
  private var proc: SupervisedProcess? = null
  private var job: Job? = null

  init { refreshInstalled() }

  fun refreshInstalled() = _state.update { it.copy(installed = File(binDir, "ngrok").canExecute()) }

  fun isRunning() = state.value.phase.let { it == AgentPhase.RUNNING || it == AgentPhase.STARTING }

  /** [region] one of "", "in", "ap", "eu", "us" ("" = auto). */
  fun start(port: Int, authToken: String, region: String) {
    if (job?.isActive == true) return
    job = scope.launch {
      try {
        if (authToken.isBlank()) throw IOException("Enter your ngrok authtoken first")
        settings.update { it.copy(ngrokEnabled = true) }
        _state.update { it.copy(message = null, addresses = emptyList()) }
        val bin = ensureBinary()
        refreshInstalled()
        val home = File(context.filesDir, "ngrok").apply { mkdirs() }
        val sp = SupervisedProcess(
          builder = {
            val cmd = mutableListOf(
              bin.absolutePath, "tcp", port.toString(), "--authtoken", authToken,
              "--log", "stdout", "--log-format", "json",
            )
            if (region.isNotBlank()) { cmd += "--region"; cmd += region }
            ProcessBuilder(cmd).directory(home).apply {
              environment()["HOME"] = home.absolutePath
              environment()["TMPDIR"] = context.cacheDir.absolutePath
              environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            }
          },
          onLine = { line ->
            log(line.replace(authToken, "***"))
            AgentParsers.ngrokTcpUrl(line)?.let { u -> _state.update { it.copy(addresses = listOf(u), phase = AgentPhase.RUNNING, message = null) } }
            AgentParsers.ngrokError(line)?.let { e -> _state.update { it.copy(message = e) } }
          },
          onPhase = { ph, msg ->
            _state.update {
              when (ph) {
                SupervisedProcess.Phase.STARTING -> it.copy(phase = AgentPhase.STARTING)
                SupervisedProcess.Phase.RUNNING -> it.copy(phase = if (it.addresses.isEmpty()) AgentPhase.STARTING else AgentPhase.RUNNING)
                SupervisedProcess.Phase.BACKOFF -> it.copy(phase = AgentPhase.STARTING, addresses = emptyList(), message = msg ?: it.message)
                SupervisedProcess.Phase.STOPPED -> it.copy(phase = AgentPhase.STOPPED, addresses = emptyList())
                SupervisedProcess.Phase.FAILED_TO_START -> it.copy(phase = AgentPhase.ERROR, message = msg)
              }
            }
          },
        )
        proc = sp
        sp.start()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(phase = AgentPhase.ERROR, message = e.message ?: e.toString()) }
      }
    }
  }

  fun stop() {
    scope.launch { settings.update { it.copy(ngrokEnabled = false) } }
    job?.cancel(); job = null
    proc?.stop(); proc = null
    _state.update { it.copy(phase = AgentPhase.STOPPED, addresses = emptyList(), message = null) }
  }

  private suspend fun ensureBinary(): File {
    val f = File(binDir, "ngrok")
    if (f.canExecute() && f.length() > 1_000_000 && isElf(f)) return f
    _state.update { it.copy(phase = AgentPhase.DOWNLOADING, message = "ngrok", progress = null) }
    val tgz = File(context.cacheDir, "ngrok.tgz")
    http.download(NGROK_URL, tgz) { p -> _state.update { s -> s.copy(progress = p) } }
    try {
      TarArchiveInputStream(GzipCompressorInputStream(tgz.inputStream().buffered())).use { tar ->
        var found = false
        while (true) {
          val e = tar.nextEntry ?: break
          if (!e.isDirectory && e.name.substringAfterLast('/') == "ngrok") {
            f.outputStream().buffered().use { out -> tar.copyTo(out) }
            found = true
            break
          }
        }
        if (!found) throw IOException("ngrok binary not found in archive")
      }
    } finally {
      tgz.delete()
    }
    if (!isElf(f)) { f.delete(); throw IOException("Extracted ngrok is not an executable") }
    f.setExecutable(true, false)
    return f
  }

  companion object {
    const val NGROK_URL = "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz"
  }
}
