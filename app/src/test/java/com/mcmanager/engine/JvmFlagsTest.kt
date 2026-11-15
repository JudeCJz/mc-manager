package com.mcmanager.engine

import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JvmFlagsTest {
  private fun server(extra: String = "", gc: String = "G1", java: Int = 21) = ServerEntity(
    name = "t", engine = Engine.PAPER, mcVersion = "1.21.4", dir = "/data/servers/1-t/",
    xmxMb = 3072, xmsMb = 1024, extraJvmArgs = extra, gc = gc, javaMajor = java,
  )

  private val aikar = JvmPreset("aikar", "Aikar", flags = listOf("-XX:+UseG1GC", "-XX:G1NewSizePercent=30", "-XX:+AlwaysPreTouch"))
  private val zgc = JvmPreset("zgc", "ZGC", flags = listOf("-XX:+UseZGC", "-XX:+ZGenerational"), minJava = 21)
  private val minimal = JvmPreset("minimal", "Minimal")

  @Test fun splitQuotes() {
    assertEquals(listOf("-Da=b c", "-Xfoo", "x y"), JvmFlags.splitArgs("""  "-Da=b c" -Xfoo 'x y' """))
    assertEquals(listOf("-Dk=v w"), JvmFlags.splitArgs("-Dk=\"v w\""))
    assertEquals(emptyList<String>(), JvmFlags.splitArgs("   "))
    assertEquals(listOf(""), JvmFlags.splitArgs("\"\""))
  }

  @Test fun basics() {
    val f = JvmFlags.build(server("-Dfoo=\"a b\""), aikar)
    assertEquals("-Xms1024M", f[0])
    assertEquals("-Xmx3072M", f[1])
    assertTrue("-XX:+UseG1GC" in f)
    assertEquals(1, f.count { it == "-XX:+UseG1GC" })
    assertTrue("-Dfoo=a b" in f)
    assertEquals("-Dfile.encoding=UTF-8", f[f.size - 2])
    assertEquals("-Djava.io.tmpdir=/data/servers/1-t/tmp", f.last())
  }

  @Test fun minimalAddsServerGc() {
    val f = JvmFlags.build(server(gc = "ZGC", java = 25), minimal)
    assertTrue("-XX:+UseZGC" in f)
    assertFalse("-XX:+ZGenerational" in f)
    assertTrue("-XX:+ZGenerational" in JvmFlags.build(server(gc = "ZGC", java = 21), minimal))
  }

  @Test fun zgcPresetSkipsGenerationalOn25() {
    assertTrue("-XX:+ZGenerational" in JvmFlags.build(server(java = 21), zgc))
    assertFalse("-XX:+ZGenerational" in JvmFlags.build(server(java = 25), zgc))
  }

  @Test fun gcOverrideReplacesPresetGc() {
    val f = JvmFlags.build(server(), aikar, "Parallel")
    assertTrue("-XX:+UseParallelGC" in f)
    assertFalse("-XX:+UseG1GC" in f)
    assertFalse("-XX:G1NewSizePercent=30" in f)
    assertTrue("-XX:+AlwaysPreTouch" in f)
  }

  @Test fun xmsClamped() {
    val f = JvmFlags.build(server().copy(xmsMb = 9000), minimal)
    assertEquals("-Xms3072M", f[0])
  }
}
