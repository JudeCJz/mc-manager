package com.mcmanager.feature.network

import java.io.StringReader
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** IPv4 / IPv6 classification helpers (pure). */
object IpClass {
  fun parseV4(ip: String): IntArray? {
    val p = ip.trim().split('.')
    if (p.size != 4) return null
    val out = IntArray(4)
    for (i in 0..3) {
      val n = p[i].toIntOrNull() ?: return null
      if (n !in 0..255) return null
      out[i] = n
    }
    return out
  }

  /** 10/8, 172.16/12, 192.168/16 */
  fun isPrivate(ip: String): Boolean {
    val a = parseV4(ip) ?: return false
    return a[0] == 10 || (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168)
  }

  /** 100.64.0.0/10 shared address space (carrier-grade NAT). */
  fun isCgnat(ip: String): Boolean {
    val a = parseV4(ip) ?: return false
    return a[0] == 100 && a[1] in 64..127
  }

  fun isLoopbackOrLinkLocal(ip: String): Boolean {
    val a = parseV4(ip) ?: return false
    return a[0] == 127 || (a[0] == 169 && a[1] == 254) || a[0] == 0
  }

  /** Global unicast IPv6 (2000::/3). */
  fun isGlobalIpv6(bytes: ByteArray): Boolean = bytes.size == 16 && (bytes[0].toInt() and 0xE0) == 0x20
}

enum class Verdict { UNKNOWN, DIRECT_OK, CGNAT, DOUBLE_NAT, MISMATCH }

object Reachability {
  /**
   * @param routerWan external IP reported by the router via UPnP (null if no UPnP)
   * @param publicV4 address seen by an external service (ipify)
   */
  fun classify(routerWan: String?, publicV4: String?): Verdict {
    if (routerWan.isNullOrBlank()) return Verdict.UNKNOWN
    if (IpClass.isCgnat(routerWan)) return Verdict.CGNAT
    if (IpClass.isPrivate(routerWan) || IpClass.isLoopbackOrLinkLocal(routerWan)) return Verdict.DOUBLE_NAT
    if (!publicV4.isNullOrBlank() && publicV4 != routerWan) return Verdict.MISMATCH
    return if (publicV4.isNullOrBlank()) Verdict.UNKNOWN else Verdict.DIRECT_OK
  }
}

/** SSDP request/response helpers. */
object Ssdp {
  const val ADDR = "239.255.255.250"
  const val PORT = 1900
  val SEARCH_TARGETS = listOf(
    "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
    "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
    "ssdp:all",
  )

  fun searchRequest(st: String): String =
    "M-SEARCH * HTTP/1.1\r\nHOST: $ADDR:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"

  /** Header map with upper-case names. */
  fun parseHeaders(response: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (line in response.lines().drop(1)) {
      val i = line.indexOf(':')
      if (i <= 0) continue
      out[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
    }
    return out
  }

  fun location(response: String): String? = parseHeaders(response)["LOCATION"]?.takeIf { it.startsWith("http") }
}

data class IgdService(val serviceType: String, val controlUrl: String)

object Igd {
  private val PREFERRED = listOf(
    "urn:schemas-upnp-org:service:WANIPConnection:2",
    "urn:schemas-upnp-org:service:WANIPConnection:1",
    "urn:schemas-upnp-org:service:WANPPPConnection:1",
  )

  fun parseXml(xml: String): org.w3c.dom.Document {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
    try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
    return f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
  }

  private fun Element.childText(tag: String): String? {
    var n: Node? = firstChild
    while (n != null) {
      if (n is Element && (n.tagName == tag || n.localName == tag)) return n.textContent.trim()
      n = n.nextSibling
    }
    return null
  }

  /** Finds the WAN connection service in an IGD description document. */
  fun parseDescription(xml: String, locationUrl: String): IgdService? {
    val doc = try { parseXml(xml) } catch (_: Exception) { return null }
    val base = doc.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: locationUrl
    val found = LinkedHashMap<String, String>()
    val services = doc.getElementsByTagName("service")
    for (i in 0 until services.length) {
      val el = services.item(i) as? Element ?: continue
      val type = el.childText("serviceType") ?: continue
      val ctl = el.childText("controlURL") ?: continue
      found[type] = ctl
    }
    val type = PREFERRED.firstOrNull { it in found }
      ?: found.keys.firstOrNull { it.contains("WANIPConnection") || it.contains("WANPPPConnection") }
      ?: return null
    return IgdService(type, resolve(base, found.getValue(type)) ?: return null)
  }

  fun resolve(base: String, rel: String): String? = try {
    URL(URL(base), rel).toString()
  } catch (_: Exception) {
    null
  }
}

class UpnpFault(val code: Int, val description: String) : Exception("UPnP error $code: $description")

object Soap {
  private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
    append("<?xml version=\"1.0\"?>")
    append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
    append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
    append("<u:$action xmlns:u=\"$serviceType\">")
    for ((k, v) in args) append("<$k>${esc(v)}</$k>")
    append("</u:$action></s:Body></s:Envelope>")
  }

  /** Output arguments of `<ActionResponse>` as a name -> value map. Empty map if none. */
  fun parseResponse(xml: String): Map<String, String> {
    val doc = Igd.parseXml(xml)
    val body = doc.getElementsByTagNameNS("*", "Body").item(0) ?: return emptyMap()
    var n: Node? = body.firstChild
    while (n != null) {
      if (n is Element) {
        val name = n.localName ?: n.tagName.substringAfter(':')
        if (name.endsWith("Response")) {
          val out = LinkedHashMap<String, String>()
          var c: Node? = n.firstChild
          while (c != null) {
            if (c is Element) out[c.localName ?: c.tagName.substringAfter(':')] = c.textContent.trim()
            c = c.nextSibling
          }
          return out
        }
      }
      n = n.nextSibling
    }
    return emptyMap()
  }

  /** UPnP error (errorCode / errorDescription) from a fault document, or null if it isn't one. */
  fun parseFault(xml: String): UpnpFault? {
    val doc = try { Igd.parseXml(xml) } catch (_: Exception) { return null }
    val code = doc.getElementsByTagName("errorCode").item(0)?.textContent?.trim()?.toIntOrNull() ?: return null
    val desc = doc.getElementsByTagName("errorDescription").item(0)?.textContent?.trim().orEmpty()
    return UpnpFault(code, desc)
  }
}
