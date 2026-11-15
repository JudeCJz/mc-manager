package com.mcmanager.core.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
  /** The device's LAN IPv4 address (prefers wlan0, then other non-loopback interfaces), or null. */
  fun lanIpv4(): String? = try {
    val addrs = NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { nif ->
        nif.inetAddresses.toList()
          .filterIsInstance<Inet4Address>()
          .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
          .map { nif.name to it.hostAddress }
      }
    (addrs.firstOrNull { it.first == "wlan0" } ?: addrs.firstOrNull())?.second
  } catch (_: Exception) {
    null
  }
}
