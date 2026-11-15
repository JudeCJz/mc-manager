package com.mcmanager.engine

import com.mcmanager.service.LogEvent
import com.mcmanager.service.LogParser
import com.mcmanager.service.StatsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogParserTest {
  @Test fun join() {
    assertEquals(listOf(LogEvent.PlayerJoined("Steve")), LogParser.parse("[12:00:01 INFO]: Steve joined the game"))
  }

  @Test fun leaveForgeStyle() {
    val l = "[07Nov2026 12:00:00.123] [Server thread/INFO] [minecraft/MinecraftServer]: Alex left the game"
    assertEquals(listOf(LogEvent.PlayerLeft("Alex")), LogParser.parse(l))
  }

  @Test fun chatIsNotJoin() {
    assertTrue(LogParser.parse("[12:00:01 INFO]: <Steve> Steve joined the game").isEmpty())
  }

  @Test fun list() {
    val e = LogParser.parse("[12:00:01 INFO]: There are 2 of a max of 20 players online: Steve, Alex")
    assertEquals(listOf<LogEvent>(LogEvent.PlayerList(2, 20, listOf("Steve", "Alex"))), e)
    val empty = LogParser.parse("[12:00:01 INFO]: There are 0 of a max of 20 players online: ")
    assertEquals(listOf<LogEvent>(LogEvent.PlayerList(0, 20, emptyList())), empty)
  }

  @Test fun tpsPaper() {
    val e = LogParser.parse("[12:00:01 INFO]: TPS from last 1m, 5m, 15m: 19.98, 20.0, 20.0")
    assertEquals(listOf<LogEvent>(LogEvent.Tps(19.98)), e)
  }

  @Test fun tpsWithColorsAndStar() {
    val e = LogParser.parse("\u001B[33m[12:00:01 INFO]: \u001B[0mTPS from last 5s, 1m, 5m, 15m: §a20.0, §a*19.5, §a20.0, §a20.0")
    assertEquals(listOf<LogEvent>(LogEvent.Tps(19.5)), e)
  }

  @Test fun done() {
    assertEquals(listOf<LogEvent>(LogEvent.Done), LogParser.parse("[12:00:01 INFO]: Done (8.123s)! For help, type \"help\""))
  }

  @Test fun stripAnsi() {
    assertEquals("hello", LogParser.stripAnsi("\u001B[31mhello\u001B[0m"))
  }

  @Test fun errorLines() {
    assertTrue(LogParser.isErrorLevel("[12:00:01 ERROR]: boom"))
    assertTrue(LogParser.isCrashReason("Error: Unable to access jarfile server.jar"))
    assertFalse(LogParser.isCrashReason("\tat net.minecraft.Foo.bar(Foo.java:1)"))
  }

  @Test fun procStat() {
    val stat = "1234 (java (x)) S 1 1 1 0 -1 4194560 100 0 0 0 150 50 0 0 20 0 30 0 100 0 0"
    assertEquals(200L, StatsSampler.parseCpuTicks(stat))
    assertEquals(512, StatsSampler.parseRssMb("Name:\tjava\nVmRSS:\t  524288 kB\n"))
  }
}
