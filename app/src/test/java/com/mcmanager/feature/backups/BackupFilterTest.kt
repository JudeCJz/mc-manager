package com.mcmanager.feature.backups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFilterTest {
  private fun inc(rel: String, dir: Boolean = false, level: String = "world", jar: String? = "server.jar") =
    BackupFilter.include(rel, dir, level, jar)

  @Test fun includesWorldsAndConfigs() {
    assertTrue(inc("world", true))
    assertTrue(inc("world_nether", true))
    assertTrue(inc("world_the_end", true))
    assertTrue(inc("world/region/r.0.0.mca"))
    assertTrue(inc("server.properties"))
    assertTrue(inc("bukkit.yml"))
    assertTrue(inc("ops.json"))
    assertTrue(inc("config", true))
    assertTrue(inc("config/paper-global.yml"))
    assertTrue(inc("plugins", true))
    assertTrue(inc("plugins/Essentials.jar"))
    assertTrue(inc("plugins/Essentials/config.yml"))
    assertTrue(inc("mods/fabric-api.jar"))
  }

  @Test fun excludesJunk() {
    assertFalse(inc("logs", true))
    assertFalse(inc("logs/latest.log"))
    assertFalse(inc("cache", true))
    assertFalse(inc("libraries", true))
    assertFalse(inc("versions", true))
    assertFalse(inc(".mcmanager", true))
    assertFalse(inc(".mcmanager/versions/x.json"))
    assertFalse(inc("crash-reports/c.txt"))
    assertFalse(inc("debug.log"))
    assertFalse(inc("world/session.lock"))
    assertFalse(inc("world/x.lck"))
    assertFalse(inc("server.jar"))
    assertFalse(inc("paper-1.21.jar"))
    assertFalse(inc("random-dir", true))
    assertFalse(inc("random-dir/file.txt"))
    assertFalse(inc("plugins/Foo/cache/data.bin"))
  }

  @Test fun customLevelNameAndAtFileLaunchSpec() {
    assertTrue(inc("survival", true, level = "survival"))
    assertTrue(inc("survival_nether/DIM-1/x", level = "survival"))
    assertFalse(inc("world", true, level = "survival"))
    assertFalse(inc("server.jar", jar = "@libraries/net/x/unix_args.txt"))
    assertEquals(listOf("w", "w_nether", "w_the_end"), BackupFilter.worldNames("w"))
  }
}
