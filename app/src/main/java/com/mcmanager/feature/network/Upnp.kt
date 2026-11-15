package com.mcmanager.feature.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Gateway(val location: String, val service: IgdService)

/** UPnP IGD client in plain Kotlin (SSDP discovery + SOAP). All calls run on Dispatchers.IO. */
class Upnp(private val context: Context) {

  /** Finds the router. Returns null when no IGD answers within the timeouts. */
  suspend fun discover(): Gateway? = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val lock = wifi?.createMulticastLock("mcmanager-ssdp")?.apply {
      setReferenceCounted(false)
      try { acquire() } catch (_: Throwable) {}
    }
    try {
      val tried = HashSet<String>()
      for (st in Ssdp.SEARCH_TARGETS) {
        for (loc in search(st)) {
          if (!tried.add(loc)) continue
          val gw = describe(loc)
          if (gw != null) return@withContext gw
        }
      }
      null
    } finally {
      try { if (lock?.isHeld == true) lock.release() } catch (_: Throwable) {}
    }
  }

  private fun search(st: String): List<String> {
    val locations = LinkedHashSet<String>()
    try {
      DatagramSocket().use { sock ->
        sock.soTimeout = 3000
        val data = Ssdp.searchRequest(st).toByteArray()
        val group = InetAddress.getByName(Ssdp.ADDR)
        repeat(2) { sock.send(DatagramPacket(data, data.size, group, Ssdp.PORT)) }
        val deadline = System.currentTimeMillis() + 3000
        val buf = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
          val p = DatagramPacket(buf, buf.size)
          try {
            sock.receive(p)
          } catch (_: SocketTimeoutException) {
            break
          }
          Ssdp.location(String(p.data, 0, p.length))?.let { locations += it }
        }
      }
    } catch (_: Exception) {
    }
    return locations.toList()
  }

  private fun describe(location: String): Gateway? = try {
    val xml = httpGet(location)
    Igd.parseDescription(xml, location)?.let { Gateway(location, it) }
  } catch (_: Exception) {
    null
  }

  private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 3000
    c.readTimeout = 4000
    try {
      return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
      c.disconnect()
    }
  }

  /** Performs a SOAP action; throws [UpnpFault] on UPnP errors. */
  private fun call(gw: Gateway, action: String, args: List<Pair<String, String>>): Map<String, String> {
    val type = gw.service.serviceType
    val body = Soap.envelope(type, action, args).toByteArray(Charsets.UTF_8)
    val c = URL(gw.service.controlUrl).openConnection() as HttpURLConnection
    c.connectTimeout = 4000
    c.readTimeout = 6000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
    c.setRequestProperty("SOAPAction", "\"$type#$action\"")
    try {
      c.outputStream.use { it.write(body) }
      val code = c.responseCode
      val stream = if (code in 200..299) c.inputStream else c.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) {
        throw Soap.parseFault(text) ?: UpnpFault(code, "HTTP $code")
      }
      return Soap.parseResponse(text)
    } finally {
      c.disconnect()
    }
  }

  suspend fun externalIp(gw: Gateway): String? = withContext(Dispatchers.IO) {
    call(gw, "GetExternalIPAddress", emptyList())["NewExternalIPAddress"]?.takeIf { it.isNotBlank() }
  }

  /**
   * Adds a mapping. Tries a permanent lease (0) first; if the router refuses
   * (e.g. error 725 OnlyPermanentLeasesSupported is the opposite case, and many routers reject 0)
   * retries with a 3600 s lease. Returns the lease actually used (0 = permanent).
   */
  suspend fun addMapping(
    gw: Gateway, port: Int, protocol: String, internalClient: String, description: String,
  ): Int = withContext(Dispatchers.IO) {
    fun add(lease: Int) = call(
      gw, "AddPortMapping",
      listOf(
        "NewRemoteHost" to "",
        "NewExternalPort" to port.toString(),
        "NewProtocol" to protocol,
        "NewInternalPort" to port.toString(),
        "NewInternalClient" to internalClient,
        "NewEnabled" to "1",
        "NewPortMappingDescription" to description,
        "NewLeaseDuration" to lease.toString(),
      ),
    )
    try {
      add(0)
      0
    } catch (e: UpnpFault) {
      add(LEASE_SECONDS)
      LEASE_SECONDS
    }
  }

  suspend fun deleteMapping(gw: Gateway, port: Int, protocol: String) = withContext(Dispatchers.IO) {
    call(
      gw, "DeletePortMapping",
      listOf("NewRemoteHost" to "", "NewExternalPort" to port.toString(), "NewProtocol" to protocol),
    )
    Unit
  }

  /** True when the router reports an existing mapping to [internalClient] for this port. */
  suspend fun hasMapping(gw: Gateway, port: Int, protocol: String, internalClient: String): Boolean =
    withContext(Dispatchers.IO) {
      try {
        val r = call(
          gw, "GetSpecificPortMappingEntry",
          listOf("NewRemoteHost" to "", "NewExternalPort" to port.toString(), "NewProtocol" to protocol),
        )
        r["NewInternalClient"] == internalClient
      } catch (_: UpnpFault) {
        false
      }
    }

  companion object {
    const val LEASE_SECONDS = 3600
  }
}
