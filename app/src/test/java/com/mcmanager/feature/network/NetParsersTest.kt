package com.mcmanager.feature.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetParsersTest {

  private val description = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
        <controlURL>/ctl/L3F</controlURL>
      </service>
    </serviceList>
    <deviceList>
      <device>
        <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
        <deviceList>
          <device>
            <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:WANIPConn1</serviceId>
                <controlURL>/ctl/IPConn</controlURL>
                <eventSubURL>/evt/IPConn</eventSubURL>
              </service>
            </serviceList>
          </device>
        </deviceList>
      </device>
    </deviceList>
  </device>
</root>"""

  @Test fun igdControlUrlResolvedAgainstLocation() {
    val s = Igd.parseDescription(description, "http://192.168.1.1:5000/rootDesc.xml")
    assertNotNull(s)
    assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", s!!.serviceType)
    assertEquals("http://192.168.1.1:5000/ctl/IPConn", s.controlUrl)
  }

  @Test fun igdUrlBaseWins() {
    val xml = description.replace("<specVersion>", "<URLBase>http://10.0.0.1:49152/</URLBase><specVersion>")
    assertEquals("http://10.0.0.1:49152/ctl/IPConn", Igd.parseDescription(xml, "http://192.168.1.1:5000/d.xml")!!.controlUrl)
  }

  @Test fun igdPrefersIpV2AndFallsBackToPpp() {
    val ppp = description.replace("WANIPConnection:1", "WANPPPConnection:1")
    assertEquals("urn:schemas-upnp-org:service:WANPPPConnection:1", Igd.parseDescription(ppp, "http://r/d.xml")!!.serviceType)
    assertNull(Igd.parseDescription("<root/>", "http://r/d.xml"))
    assertNull(Igd.parseDescription("not xml", "http://r/d.xml"))
  }

  @Test fun ssdpLocationParsed() {
    val resp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: urn:x\r\n\r\n"
    assertEquals("http://192.168.1.1:5000/rootDesc.xml", Ssdp.location(resp))
    assertNull(Ssdp.location("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
    assertTrue(Ssdp.searchRequest("ssdp:all").contains("MAN: \"ssdp:discover\""))
  }

  @Test fun soapEnvelopeEscapesAndHasAction() {
    val x = Soap.envelope("urn:t:1", "AddPortMapping", listOf("NewPortMappingDescription" to "A & <B>"))
    assertTrue(x.contains("<u:AddPortMapping xmlns:u=\"urn:t:1\">"))
    assertTrue(x.contains("A &amp; &lt;B&gt;"))
    assertNotNull(Igd.parseXml(x))
  }

  @Test fun soapResponseParsed() {
    val xml = """<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:GetExternalIPAddressResponse xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">
<NewExternalIPAddress>203.0.113.7</NewExternalIPAddress></u:GetExternalIPAddressResponse></s:Body></s:Envelope>"""
    assertEquals("203.0.113.7", Soap.parseResponse(xml)["NewExternalIPAddress"])
  }

  @Test fun soapFaultParsed() {
    val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>725</errorCode>
<errorDescription>OnlyPermanentLeasesSupported</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
    val f = Soap.parseFault(xml)
    assertNotNull(f)
    assertEquals(725, f!!.code)
    assertEquals("OnlyPermanentLeasesSupported", f.description)
    assertNull(Soap.parseFault("<a/>"))
  }

  @Test fun ipClassification() {
    assertTrue(IpClass.isPrivate("10.1.2.3"))
    assertTrue(IpClass.isPrivate("172.16.0.1"))
    assertTrue(IpClass.isPrivate("172.31.255.255"))
    assertFalse(IpClass.isPrivate("172.32.0.1"))
    assertTrue(IpClass.isPrivate("192.168.43.1"))
    assertFalse(IpClass.isPrivate("8.8.8.8"))
    assertTrue(IpClass.isCgnat("100.64.0.1"))
    assertTrue(IpClass.isCgnat("100.127.255.255"))
    assertFalse(IpClass.isCgnat("100.128.0.1"))
    assertFalse(IpClass.isCgnat("100.63.0.1"))
    assertFalse(IpClass.isCgnat("garbage"))
  }

  @Test fun globalIpv6() {
    val global = ByteArray(16).also { it[0] = 0x24; it[1] = 0x06 }
    val linkLocal = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte() }
    val ula = ByteArray(16).also { it[0] = 0xfd.toByte() }
    assertTrue(IpClass.isGlobalIpv6(global))
    assertFalse(IpClass.isGlobalIpv6(linkLocal))
    assertFalse(IpClass.isGlobalIpv6(ula))
  }

  @Test fun reachabilityVerdicts() {
    assertEquals(Verdict.CGNAT, Reachability.classify("100.70.1.1", "49.1.1.1"))
    assertEquals(Verdict.DOUBLE_NAT, Reachability.classify("192.168.0.5", "49.1.1.1"))
    assertEquals(Verdict.MISMATCH, Reachability.classify("49.1.1.2", "49.1.1.1"))
    assertEquals(Verdict.DIRECT_OK, Reachability.classify("49.1.1.1", "49.1.1.1"))
    assertEquals(Verdict.UNKNOWN, Reachability.classify(null, "49.1.1.1"))
    assertEquals(Verdict.UNKNOWN, Reachability.classify("49.1.1.1", null))
  }

  @Test fun serverPropsDiffAndApply() {
    val text = "#Minecraft\nview-distance=10\nserver-port=25565\nsync-chunk-writes=true\n"
    val d = ServerProps.diff(text, ServerProps.LOW_LATENCY)
    assertEquals(4, d.size)
    assertEquals("10", d.first { it.key == "view-distance" }.old)
    assertNull(d.first { it.key == "network-compression-threshold" }.old)
    val out = ServerProps.apply(text, ServerProps.LOW_LATENCY)
    assertTrue(out.startsWith("#Minecraft\nview-distance=8\nserver-port=25565\nsync-chunk-writes=false\n"))
    assertTrue(out.contains("network-compression-threshold=256"))
    assertTrue(ServerProps.diff(out, ServerProps.LOW_LATENCY).isEmpty())
    assertEquals("server-port=1234\n", ServerProps.apply("", mapOf("server-port" to "1234")))
  }

  @Test fun agentParsers() {
    assertEquals("tcp-name.joinmc.link", AgentParsers.playitAddress("tunnel ready at tcp-name.joinmc.link now"))
    assertEquals("https://playit.gg/claim/abcdef0123", AgentParsers.playitClaimUrl("visit https://playit.gg/claim/abcdef0123 please"))
    assertEquals("0.tcp.in.ngrok.io:12345",
      AgentParsers.ngrokTcpUrl("""t=1 lvl=info msg="started tunnel" "url":"tcp://0.tcp.in.ngrok.io:12345" """))
    assertEquals("authentication failed",
      AgentParsers.ngrokError("""{"lvl":"eror","err":"authentication failed"}"""))
    assertNull(AgentParsers.ngrokError("""{"lvl":"info","msg":"ok"}"""))
    assertEquals("a".repeat(64), AgentParsers.playitSecret("Preparing setup...\n" + "a".repeat(64) + "\n"))
  }
}
