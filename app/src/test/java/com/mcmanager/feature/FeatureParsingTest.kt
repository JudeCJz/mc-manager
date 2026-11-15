package com.mcmanager.feature

import com.mcmanager.feature.config.PropertiesFile
import com.mcmanager.feature.config.PropertiesSchema
import com.mcmanager.feature.players.OpEntry
import com.mcmanager.feature.players.PlayerLists
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureParsingTest {
  private val sample = "#Minecraft server properties\n#Sat Nov 07 10:00:00 UTC 2026\n" +
    "difficulty=easy\n\n# my note\nlevel-type=minecraft\\:normal\nmotd=Hello \\u00A7aWorld\nmax-players=20\n"

  @Test
  fun roundTripUnchanged() {
    val p = PropertiesFile.parse(sample)
    assertEquals(sample, p.render())
    assertEquals("minecraft:normal", p.get("level-type"))
    assertEquals("Hello \u00a7aWorld", p.get("motd"))
  }

  @Test
  fun setKeepsCommentsAndOrder() {
    val p = PropertiesFile.parse(sample)
    p.set("difficulty", "hard")
    p.set("level-type", "minecraft:flat")
    p.set("new-key", "x y")
    val out = p.render()
    assertEquals(
      "#Minecraft server properties\n#Sat Nov 07 10:00:00 UTC 2026\n" +
        "difficulty=hard\n\n# my note\nlevel-type=minecraft\\:flat\nmotd=Hello \\u00A7aWorld\nmax-players=20\nnew-key=x y\n",
      out,
    )
    assertEquals(listOf("difficulty", "level-type", "motd", "max-players", "new-key"), PropertiesFile.parse(out).keys())
  }

  @Test
  fun schemaValidation() {
    val d = PropertiesSchema.def("view-distance")!!
    assertNull(PropertiesSchema.validate(d, "10"))
    assertEquals("Maximum 32", PropertiesSchema.validate(d, "40"))
  }

  @Test
  fun offlineUuidMatchesVanilla() {
    // Known vanilla offline UUID for "Notch" is b50ad385-829d-3141-a216-7e7d7539ba7f
    assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), PlayerLists.offlineUuid("Notch"))
  }

  @Test
  fun dashUuid() {
    assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", PlayerLists.dashUuid("069a79f444e94726a5befca90e38aaf5"))
    assertNull(PlayerLists.dashUuid("nope"))
  }

  @Test
  fun opsRoundTrip() {
    val ops = listOf(OpEntry("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch", 3, true))
    assertEquals(ops, PlayerLists.parseOps(PlayerLists.renderOps(ops)))
  }
}
