package com.mcmanager.feature.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.util.NetUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ForwardPrefs(val enabled: Boolean = false, val udp: Boolean = false, val removeOnStop: Boolean = true)

data class NetPrefs(
  val servers: Map<Long, ForwardPrefs> = emptyMap(),
  val tunnelsStopWithServers: Boolean = false,
  val ngrokRegion: String = "",
) {
  fun forServer(id: Long) = servers[id] ?: ForwardPrefs()
}

data class ForwardStatus(
  val busy: Boolean = false,
  val active: Boolean = false,
  val upnpFound: Boolean? = null,
  val routerWanIp: String? = null,
  val publicV4: String? = null,
  val publicV6: String? = null,
  val verdict: Verdict = Verdict.UNKNOWN,
  val leaseSeconds: Int = 0,
  val error: String? = null,
)

enum class ConnKind { WIFI, MOBILE, ETHERNET, NONE }

data class NetInfo(
  val kind: ConnKind,
  val lanIp: String?,
  val hotspotIp: String?,
  val ipv6: List<String>,
)

@Singleton
class NetworkManager @Inject constructor(
  @ApplicationContext private val context: Context,
  private val controller: ServerController,
  private val servers: ServerRepository,
  private val settings: SettingsRepository,
  private val http: OkHttpClient,
  val playit: PlayitAgent,
  val ngrok: NgrokAgent,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val upnp = Upnp(context)
  private val upnpLock = Mutex()
  @Volatile private var gateway: Gateway? = null
  private var started = false

  private data class Mapped(val port: Int, val udp: Boolean, val lease: Int)

  private val mapped = HashMap<Long, Mapped>()
  private val renewJobs = HashMap<Long, Job>()

  private val prefsFile get() = File(context.filesDir, "network_prefs.json")
  private val _prefs = MutableStateFlow(loadPrefs())
  val prefs: StateFlow<NetPrefs> = _prefs

  private val _forward = MutableStateFlow<Map<Long, ForwardStatus>>(emptyMap())
  val forward: StateFlow<Map<Long, ForwardStatus>> = _forward

  /** Idempotent. Call once at app start (and it is also called by the Network screen). */
  fun init() {
    synchronized(this) {
      if (started) return
      started = true
    }
    scope.launch { watchServers() }
    scope.launch {
      if (settings.settings.first().playitEnabled && playit.state.value.installed) playit.start()
    }
  }

  // ---- prefs ----------------------------------------------------------------------------

  private fun loadPrefs(): NetPrefs = try {
    val f = prefsFile
    if (!f.isFile) NetPrefs() else {
      val o = JSONObject(f.readText())
      val m = HashMap<Long, ForwardPrefs>()
      o.optJSONObject("servers")?.let { s ->
        for (k in s.keys()) {
          val v = s.getJSONObject(k)
          k.toLongOrNull()?.let {
            m[it] = ForwardPrefs(v.optBoolean("enabled"), v.optBoolean("udp"), v.optBoolean("removeOnStop", true))
          }
        }
      }
      NetPrefs(m, o.optBoolean("tunnelsStop"), o.optString("ngrokRegion", ""))
    }
  } catch (_: Exception) {
    NetPrefs()
  }

  private fun savePrefs(p: NetPrefs) {
    _prefs.value = p
    try {
      val s = JSONObject()
      for ((id, f) in p.servers) {
        s.put(id.toString(), JSONObject().put("enabled", f.enabled).put("udp", f.udp).put("removeOnStop", f.removeOnStop))
      }
      prefsFile.writeText(JSONObject().put("servers", s).put("tunnelsStop", p.tunnelsStopWithServers).put("ngrokRegion", p.ngrokRegion).toString())
    } catch (_: Exception) {
    }
  }

  private fun updateForwardPrefs(id: Long, t: (ForwardPrefs) -> ForwardPrefs) {
    val p = _prefs.value
    savePrefs(p.copy(servers = p.servers + (id to t(p.forServer(id)))))
  }

  fun setTunnelsStopWithServers(v: Boolean) = savePrefs(_prefs.value.copy(tunnelsStopWithServers = v))
  fun setNgrokRegion(r: String) = savePrefs(_prefs.value.copy(ngrokRegion = r))
  fun setRemoveOnStop(id: Long, v: Boolean) = updateForwardPrefs(id) { it.copy(removeOnStop = v) }

  suspend fun setUdp(id: Long, v: Boolean) {
    updateForwardPrefs(id) { it.copy(udp = v) }
    if (_prefs.value.forServer(id).enabled) {
      removeForward(id)
      applyForward(id)
    }
  }

  suspend fun setForwardEnabled(id: Long, enabled: Boolean) {
    updateForwardPrefs(id) { it.copy(enabled = enabled) }
    if (enabled) applyForward(id) else removeForward(id)
  }

  // ---- server lifecycle watcher -------------------------------------------------------------

  private suspend fun watchServers() {
    var prev: Map<Long, ServerState> = emptyMap()
    controller.runtimes.collect { m ->
      val cur = m.mapValues { it.value.state }
      for ((id, st) in cur) {
        val old = prev[id] ?: ServerState.STOPPED
        val p = _prefs.value.forServer(id)
        if (st == ServerState.RUNNING && old != ServerState.RUNNING && p.enabled) {
          launchSafely { applyForward(id) }
        }
        val wasUp = old == ServerState.RUNNING || old == ServerState.STARTING || old == ServerState.STOPPING
        if ((st == ServerState.STOPPED || st == ServerState.CRASHED) && wasUp && p.enabled && p.removeOnStop) {
          launchSafely { removeForward(id) }
        }
      }
      if (_prefs.value.tunnelsStopWithServers && prev.isNotEmpty() &&
        prev.values.any { it == ServerState.RUNNING || it == ServerState.STARTING } &&
        cur.values.none { it == ServerState.RUNNING || it == ServerState.STARTING || it == ServerState.STOPPING }
      ) {
        playit.stop()
        ngrok.stop()
      }
      prev = cur
    }
  }

  private fun launchSafely(block: suspend () -> Unit) {
    scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
  }

  // ---- UPnP -------------------------------------------------------------------------------------

  private fun setStatus(id: Long, t: (ForwardStatus) -> ForwardStatus) =
    _forward.update { it + (id to t(it[id] ?: ForwardStatus())) }

  private suspend fun gateway(force: Boolean = false): Gateway? {
    if (!force) gateway?.let { return it }
    return upnp.discover().also { gateway = it }
  }

  /** Maps the server port on the router (and re-checks reachability). Safe to call repeatedly. */
  suspend fun applyForward(id: Long) {
    upnpLock.withLock {
      setStatus(id) { it.copy(busy = true, error = null) }
      try {
        val server = servers.get(id) ?: return
        val lan = withContext(Dispatchers.IO) { NetUtils.lanIpv4() }
        if (lan == null) {
          setStatus(id) { it.copy(busy = false, active = false, error = "No LAN address (not on Wi-Fi?)") }
          return
        }
        val udp = _prefs.value.forServer(id).udp
        val first = gateway()
        if (first == null) {
          setStatus(id) { it.copy(busy = false, active = false, upnpFound = false) }
          refreshPublic(id, null)
          return
        }
        var gw: Gateway = first
        val desc = "MCManager ${server.name}".take(60)
        val lease = try {
          upnp.addMapping(gw, server.port, "TCP", lan, desc)
        } catch (e: java.io.IOException) {
          gw = gateway(force = true) ?: throw e
          upnp.addMapping(gw, server.port, "TCP", lan, desc)
        }
        if (udp) upnp.addMapping(gw, server.port, "UDP", lan, desc)
        val ok = upnp.hasMapping(gw, server.port, "TCP", lan)
        val wan = try { upnp.externalIp(gw) } catch (_: Exception) { null }
        mapped[id] = Mapped(server.port, udp, lease)
        scheduleRenew(id, lan, desc)
        setStatus(id) { it.copy(busy = false, active = ok, upnpFound = true, routerWanIp = wan, leaseSeconds = lease, error = if (ok) null else "Router accepted the mapping but does not list it") }
        refreshPublic(id, wan)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        setStatus(id) { it.copy(busy = false, active = false, error = e.message ?: e.toString()) }
      } finally {
        setStatus(id) { it.copy(busy = false) }
      }
    }
  }

  private fun scheduleRenew(id: Long, lan: String, desc: String) {
    renewJobs.remove(id)?.cancel()
    val m = mapped[id] ?: return
    if (m.lease == 0) return
    renewJobs[id] = scope.launch {
      while (true) {
        delay(TimeUnit.MINUTES.toMillis(30))
        val gw = gateway ?: break
        try {
          upnpLock.withLock {
            upnp.addMapping(gw, m.port, "TCP", NetUtils.lanIpv4() ?: lan, desc)
            if (m.udp) upnp.addMapping(gw, m.port, "UDP", NetUtils.lanIpv4() ?: lan, desc)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
        }
      }
    }
  }

  suspend fun removeForward(id: Long) {
    upnpLock.withLock {
      renewJobs.remove(id)?.cancel()
      val m = mapped.remove(id)
      val gw = gateway
      if (m != null && gw != null) {
        try { upnp.deleteMapping(gw, m.port, "TCP") } catch (_: Exception) {}
        if (m.udp) try { upnp.deleteMapping(gw, m.port, "UDP") } catch (_: Exception) {}
      }
      setStatus(id) { it.copy(busy = false, active = false, leaseSeconds = 0) }
    }
  }

  /** Public IP lookups + router WAN IP (UPnP) -> verdict. Does not touch the mapping. */
  suspend fun checkReachability(id: Long) {
    setStatus(id) { it.copy(busy = true) }
    try {
      val gw = gateway()
      val wan = gw?.let { try { upnp.externalIp(it) } catch (_: Exception) { null } }
      setStatus(id) { it.copy(upnpFound = gw != null, routerWanIp = wan ?: it.routerWanIp) }
      refreshPublic(id, wan)
    } finally {
      setStatus(id) { it.copy(busy = false) }
    }
  }

  private suspend fun refreshPublic(id: Long, wan: String?) {
    val v4 = fetchText("https://api.ipify.org")?.takeIf { IpClass.parseV4(it) != null }
    val v6 = fetchText("https://api64.ipify.org")?.takeIf { it.contains(':') }
    val router = wan ?: _forward.value[id]?.routerWanIp
    setStatus(id) { it.copy(publicV4 = v4, publicV6 = v6, verdict = Reachability.classify(router, v4)) }
  }

  private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
    try {
      val c = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
      c.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (r.isSuccessful) r.body.string().trim().take(64) else null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  // ---- local network info -----------------------------------------------------------------------

  suspend fun netInfo(): NetInfo = withContext(Dispatchers.IO) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val kind = when {
      caps == null -> ConnKind.NONE
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnKind.WIFI
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnKind.ETHERNET
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnKind.MOBILE
      else -> ConnKind.NONE
    }
    var hotspot: String? = null
    val v6 = ArrayList<String>()
    try {
      for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
        if (!nif.isUp || nif.isLoopback) continue
        val isAp = nif.name.startsWith("ap") || nif.name.startsWith("swlan") || nif.name.startsWith("softap")
        for (a in nif.inetAddresses.toList()) {
          if (a is Inet4Address && isAp && hotspot == null) hotspot = a.hostAddress
          if (a is Inet6Address && !isAp && IpClass.isGlobalIpv6(a.address)) {
            v6 += (a.hostAddress ?: continue).substringBefore('%')
          }
        }
      }
    } catch (_: Exception) {
    }
    NetInfo(kind, NetUtils.lanIpv4(), hotspot, v6)
  }

  // ---- port + server.properties ------------------------------------------------------------------

  suspend fun changePort(id: Long, newPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val server = servers.get(id) ?: return@withContext Result.failure(IllegalStateException("Server not found"))
      val st = controller.runtime(id).value.state
      if (st != ServerState.STOPPED && st != ServerState.CRASHED) {
        return@withContext Result.failure(IllegalStateException("Stop the server first"))
      }
      if (newPort !in 1024..65535) return@withContext Result.failure(IllegalArgumentException("Port must be 1024-65535"))
      if (mapped.containsKey(id)) removeForward(id)
      servers.update(server.copy(port = newPort))
      ServerProps.write(server.dir, mapOf("server-port" to newPort.toString()))
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun latencyDiff(id: Long): List<ServerProps.Change> = withContext(Dispatchers.IO) {
    val s = servers.get(id) ?: return@withContext emptyList()
    ServerProps.diff(ServerProps.read(s.dir), ServerProps.LOW_LATENCY)
  }

  suspend fun applyLatency(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      val s = servers.get(id) ?: return@withContext Result.failure(IllegalStateException("Server not found"))
      ServerProps.write(s.dir, ServerProps.LOW_LATENCY)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
