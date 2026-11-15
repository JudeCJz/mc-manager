package com.mcmanager.feature.players

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.R
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.net.HttpException
import com.mcmanager.core.net.getString
import com.mcmanager.feature.config.PropertiesFile
import com.mcmanager.feature.files.FileOps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class PlayersUi(
  val loading: Boolean = true,
  val serverMissing: Boolean = false,
  val serverState: ServerState = ServerState.STOPPED,
  val online: List<String> = emptyList(),
  val whitelist: List<WhitelistEntry> = emptyList(),
  val ops: List<OpEntry> = emptyList(),
  val bans: List<BanEntry> = emptyList(),
  val ipBans: List<IpBanEntry> = emptyList(),
  val whitelistOn: Boolean = false,
  val onlineMode: Boolean = true,
  val busy: Boolean = false,
) {
  val running: Boolean get() = serverState == ServerState.RUNNING
}

private class Identity(val uuid: String, val name: String)

@HiltViewModel
class PlayersViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val servers: ServerRepository,
  private val controller: ServerController,
  private val http: OkHttpClient,
) : ViewModel() {

  private val _state = MutableStateFlow(PlayersUi())
  val state: StateFlow<PlayersUi> = _state.asStateFlow()

  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  private var serverId = -1L
  private var dir: File? = null

  fun init(id: Long) {
    if (serverId == id) return
    serverId = id
    viewModelScope.launch {
      val s = servers.get(id)
      if (s == null) {
        _state.update { it.copy(loading = false, serverMissing = true) }
        return@launch
      }
      dir = File(s.dir)
      launch {
        controller.runtime(id).collectLatest { rt ->
          _state.update { it.copy(serverState = rt.state, online = rt.onlinePlayers) }
        }
      }
      loadAll()
    }
  }

  private fun err(res: Int, vararg a: Any) {
    _messages.trySend(context.getString(res, *a))
  }

  fun loadAll() {
    val d = dir ?: return
    viewModelScope.launch {
      val r = withContext(Dispatchers.IO) {
        fun read(n: String) = File(d, n).takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() } ?: ""
        val props = File(d, "server.properties").takeIf { it.isFile }
          ?.let { runCatching { PropertiesFile.parse(it.readText(Charsets.UTF_8)) }.getOrNull() }
        listOf(
          PlayerLists.parseWhitelist(read("whitelist.json")),
          PlayerLists.parseOps(read("ops.json")),
          PlayerLists.parseBans(read("banned-players.json")),
          PlayerLists.parseIpBans(read("banned-ips.json")),
          props?.get("white-list") == "true",
          props?.get("online-mode") != "false",
        )
      }
      @Suppress("UNCHECKED_CAST")
      _state.update {
        it.copy(
          loading = false,
          whitelist = r[0] as List<WhitelistEntry>,
          ops = r[1] as List<OpEntry>,
          bans = r[2] as List<BanEntry>,
          ipBans = r[3] as List<IpBanEntry>,
          whitelistOn = r[4] as Boolean,
          onlineMode = r[5] as Boolean,
        )
      }
    }
  }

  fun refreshOnline() {
    if (_state.value.running) controller.sendCommand(serverId, "list")
  }

  // --- helpers -----------------------------------------------------------------------------

  private val nameRegex = Regex("^\\.?[A-Za-z0-9_]{1,16}$")
  private val ipRegex = Regex("^[0-9a-fA-F:.]{3,45}$")

  private suspend fun lookup(url: String): Pair<String, String>? {
    val body = try {
      http.getString(url)
    } catch (e: HttpException) {
      if (e.code == 404 || e.code == 204) return null
      throw e
    }
    return PlayerLists.parseMojangProfile(body)
  }

  /** Resolves a name or UUID. Returns null (after posting a message) on failure. */
  private suspend fun resolve(input: String, needUuid: Boolean): Identity? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val online = _state.value.onlineMode
    try {
      if (PlayerLists.looksLikeUuid(text)) {
        if (!online) {
          err(R.string.players_err_uuid_offline)
          return null
        }
        val p = lookup("https://sessionserver.mojang.com/session/minecraft/profile/" + text.replace("-", ""))
        if (p == null) {
          err(R.string.players_not_found)
          return null
        }
        return Identity(p.first, p.second)
      }
      if (!nameRegex.matches(text)) {
        err(R.string.players_err_name)
        return null
      }
      if (!needUuid) return Identity("", text)
      if (!online) return Identity(PlayerLists.offlineUuid(text).toString(), text)
      val p = lookup("https://api.mojang.com/users/profiles/minecraft/$text")
      if (p == null) {
        err(R.string.players_not_found)
        return null
      }
      return Identity(p.first, p.second)
    } catch (e: Exception) {
      err(R.string.players_err_network)
      return null
    }
  }

  private suspend fun <T> editJson(
    file: String,
    parse: (String) -> List<T>,
    render: (List<T>) -> String,
    change: (List<T>) -> List<T>,
  ) {
    val f = File(dir!!, file)
    withContext(Dispatchers.IO) {
      val cur = if (f.isFile) parse(f.readText(Charsets.UTF_8)) else emptyList()
      FileOps.atomicWrite(f, render(change(cur)))
    }
  }

  private fun launchWork(block: suspend () -> Unit) {
    viewModelScope.launch {
      _state.update { it.copy(busy = true) }
      try {
        block()
      } catch (e: Exception) {
        _messages.trySend(e.message ?: context.getString(R.string.error_generic))
      }
      _state.update { it.copy(busy = false) }
    }
  }

  /** Runs [cmd] if the server is running (then reloads 2 s later), otherwise [direct] edits the files. */
  private suspend fun execute(cmd: String?, direct: suspend () -> Unit) {
    if (_state.value.running && cmd != null) {
      controller.sendCommand(serverId, cmd)
      delay(2000)
    } else {
      direct()
    }
    loadAll()
  }

  private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

  // --- online ------------------------------------------------------------------------------

  fun kick(name: String, reason: String) {
    controller.sendCommand(serverId, "kick $name ${clean(reason)}".trim())
  }

  fun opOnline(name: String, op: Boolean) {
    controller.sendCommand(serverId, (if (op) "op " else "deop ") + name)
    launchWork { delay(2000); loadAll() }
  }

  fun whitelistOnline(name: String) = addWhitelist(name)

  // --- whitelist ---------------------------------------------------------------------------

  fun addWhitelist(input: String) = launchWork {
    val running = _state.value.running
    val id = resolve(input, needUuid = !running || PlayerLists.looksLikeUuid(input)) ?: return@launchWork
    execute("whitelist add ${id.name}") {
      editJson("whitelist.json", PlayerLists::parseWhitelist, PlayerLists::renderWhitelist) { l ->
        l.filterNot { it.name.equals(id.name, true) } + WhitelistEntry(id.uuid, id.name)
      }
    }
  }

  fun removeWhitelist(e: WhitelistEntry) = launchWork {
    execute("whitelist remove ${e.name}") {
      editJson("whitelist.json", PlayerLists::parseWhitelist, PlayerLists::renderWhitelist) { l ->
        l.filterNot { it.name.equals(e.name, true) }
      }
    }
  }

  fun setWhitelistEnabled(on: Boolean) = launchWork {
    execute("whitelist " + if (on) "on" else "off") {
      val f = File(dir!!, "server.properties")
      withContext(Dispatchers.IO) {
        val p = if (f.isFile) PropertiesFile.parse(f.readText(Charsets.UTF_8)) else PropertiesFile.empty()
        p.set("white-list", on.toString())
        FileOps.atomicWrite(f, p.render())
      }
    }
  }

  // --- ops ---------------------------------------------------------------------------------

  fun addOp(input: String, level: Int) = launchWork {
    val running = _state.value.running
    val id = resolve(input, needUuid = !running || PlayerLists.looksLikeUuid(input)) ?: return@launchWork
    execute("op ${id.name}") {
      editJson("ops.json", PlayerLists::parseOps, PlayerLists::renderOps) { l ->
        l.filterNot { it.name.equals(id.name, true) } + OpEntry(id.uuid, id.name, level.coerceIn(1, 4), false)
      }
    }
  }

  fun removeOp(e: OpEntry) = launchWork {
    execute("deop ${e.name}") {
      editJson("ops.json", PlayerLists::parseOps, PlayerLists::renderOps) { l ->
        l.filterNot { it.name.equals(e.name, true) }
      }
    }
  }

  /** Only possible while stopped (the server rewrites ops.json from memory otherwise). */
  fun setOpLevel(e: OpEntry, level: Int) = launchWork {
    if (_state.value.running) return@launchWork err(R.string.players_op_level_stopped)
    editJson("ops.json", PlayerLists::parseOps, PlayerLists::renderOps) { l ->
      l.map { if (it.name.equals(e.name, true)) it.copy(level = level) else it }
    }
    loadAll()
  }

  // --- bans --------------------------------------------------------------------------------

  fun ban(input: String, reason: String) = launchWork {
    val running = _state.value.running
    val id = resolve(input, needUuid = !running || PlayerLists.looksLikeUuid(input)) ?: return@launchWork
    val r = clean(reason).ifEmpty { "Banned by an operator." }
    execute("ban ${id.name} $r") {
      editJson("banned-players.json", PlayerLists::parseBans, PlayerLists::renderBans) { l ->
        l.filterNot { it.name.equals(id.name, true) } +
          BanEntry(id.uuid, id.name, PlayerLists.banTimestamp(), "Server", "forever", r)
      }
    }
  }

  fun pardon(e: BanEntry) = launchWork {
    execute("pardon ${e.name}") {
      editJson("banned-players.json", PlayerLists::parseBans, PlayerLists::renderBans) { l ->
        l.filterNot { it.name.equals(e.name, true) }
      }
    }
  }

  fun banIp(ip: String, reason: String) = launchWork {
    val addr = ip.trim()
    if (!ipRegex.matches(addr) || !(addr.contains('.') || addr.contains(':'))) return@launchWork err(R.string.players_err_ip)
    val r = clean(reason).ifEmpty { "Banned by an operator." }
    execute("ban-ip $addr $r") {
      editJson("banned-ips.json", PlayerLists::parseIpBans, PlayerLists::renderIpBans) { l ->
        l.filterNot { it.ip == addr } + IpBanEntry(addr, PlayerLists.banTimestamp(), "Server", "forever", r)
      }
    }
  }

  fun pardonIp(e: IpBanEntry) = launchWork {
    execute("pardon-ip ${e.ip}") {
      editJson("banned-ips.json", PlayerLists::parseIpBans, PlayerLists::renderIpBans) { l ->
        l.filterNot { it.ip == e.ip }
      }
    }
  }
}
