package com.mcmanager.feature.plugins

import com.mcmanager.core.model.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatTest {

  @Test fun parse() {
    assertEquals(listOf(1, 21, 4), Compat.parseVersion("1.21.4"))
    assertEquals(listOf(1, 21), Compat.parseVersion("1.21-pre1"))
    assertEquals(listOf(26, 1), Compat.parseVersion("26.1"))
    assertEquals(null, Compat.parseVersion("snapshot"))
  }

  @Test fun fabricRanges() {
    assertTrue(Compat.matches(">=1.21 <1.22", "1.21.4"))
    assertTrue(Compat.matches(">=1.21 <1.22", "1.21"))
    assertFalse(Compat.matches(">=1.21 <1.22", "1.22"))
    assertFalse(Compat.matches(">=1.21 <1.22", "1.20.6"))
    assertTrue(Compat.matches("~1.21.4", "1.21.4"))
    assertTrue(Compat.matches("~1.21.4", "1.21.10"))
    assertFalse(Compat.matches("~1.21.4", "1.21.3"))
    assertFalse(Compat.matches("~1.21.4", "1.22.0"))
    assertTrue(Compat.matches("~1.21", "1.21.7"))
    assertFalse(Compat.matches("~1.21", "1.22"))
    assertTrue(Compat.matches("1.21.x", "1.21.9"))
    assertTrue(Compat.matches("1.21.x", "1.21"))
    assertFalse(Compat.matches("1.21.x", "1.20.1"))
    assertTrue(Compat.matches("1.x", "1.8"))
    assertTrue(Compat.matches("^1.20", "1.21.1"))
    assertFalse(Compat.matches("^1.20", "2.0"))
    assertTrue(Compat.matches("1.21", "1.21.0"))
    assertFalse(Compat.matches("1.21", "1.21.1"))
    assertTrue(Compat.matches("*", "1.21.1"))
    assertTrue(Compat.matches(">=1.19 || 1.18.x", "1.18.2"))
    assertFalse(Compat.matches(">1.21", "1.21"))
    assertTrue(Compat.matches("<=1.21", "1.21"))
  }

  @Test fun mavenRanges() {
    assertTrue(Compat.matches("[1.20.1,1.21)", "1.20.1"))
    assertTrue(Compat.matches("[1.20.1,1.21)", "1.20.6"))
    assertFalse(Compat.matches("[1.20.1,1.21)", "1.21"))
    assertFalse(Compat.matches("[1.20.1,1.21)", "1.20"))
    assertTrue(Compat.matches("[1.20,)", "1.21.4"))
    assertTrue(Compat.matches("(,1.21]", "1.21"))
    assertFalse(Compat.matches("(,1.21)", "1.21"))
    assertTrue(Compat.matches("[1.20.1]", "1.20.1"))
    assertFalse(Compat.matches("[1.20.1]", "1.20.2"))
    assertTrue(Compat.matches("[1.20,1.21),[1.21.2,)", "1.21.4"))
    assertFalse(Compat.matches("[1.20,1.21),[1.21.2,)", "1.21.1"))
  }

  @Test fun unparseableNeverWarns() {
    assertTrue(Compat.matches(">=1.21", "weird"))
    assertTrue(Compat.matchesAny(emptyList(), "1.21"))
  }

  @Test fun apiVersion() {
    assertTrue(Compat.apiVersionOk("1.20", "1.21.4"))
    assertTrue(Compat.apiVersionOk("1.21", "1.21.4"))
    assertFalse(Compat.apiVersionOk("1.22", "1.21.4"))
    assertTrue(Compat.apiVersionOk("1.13", "1.21.4"))
  }

  @Test fun wrongLoader() {
    val issues = Compat.check(
      Engine.PAPER, "1.21.4", setOf(JarKind.FABRIC), emptyList(), null, emptyList(), emptySet(),
    )
    assertEquals(1, issues.size)
    assertTrue(issues[0] is PluginIssue.WrongLoader)
    assertEquals("plugins", (issues[0] as PluginIssue.WrongLoader).expectedFolder)

    val bukkitInMods = Compat.check(
      Engine.FABRIC, "1.21.4", setOf(JarKind.BUKKIT), emptyList(), null, emptyList(), emptySet(),
    )
    assertTrue(bukkitInMods.single() is PluginIssue.WrongLoader)

    val forgeOnFabric = Compat.check(
      Engine.FABRIC, "1.21.4", setOf(JarKind.FORGE), emptyList(), null, emptyList(), emptySet(),
    )
    assertTrue(forgeOnFabric.single() is PluginIssue.WrongLoader)

    val fabricOnForge = Compat.check(
      Engine.FORGE, "1.20.1", setOf(JarKind.FABRIC), emptyList(), null, emptyList(), emptySet(),
    )
    assertTrue(fabricOnForge.single() is PluginIssue.WrongLoader)

    // Hybrid jar is fine as long as one descriptor fits.
    val hybrid = Compat.check(
      Engine.PAPER, "1.21.4", setOf(JarKind.BUKKIT, JarKind.FABRIC), emptyList(), null, emptyList(), emptySet(),
    )
    assertTrue(hybrid.isEmpty())
  }

  @Test fun mcMismatchAndDeps() {
    val issues = Compat.check(
      Engine.FABRIC, "1.21.4", setOf(JarKind.FABRIC), listOf(">=1.20 <1.21"), null,
      listOf("fabric-api", "Other"), setOf("FABRIC-API"),
    )
    assertEquals(2, issues.size)
    assertTrue(issues[0] is PluginIssue.McMismatch)
    assertEquals(listOf("Other"), (issues[1] as PluginIssue.MissingDeps).names)
  }

  @Test fun apiTooNew() {
    val issues = Compat.check(Engine.PAPER, "1.20.4", setOf(JarKind.BUKKIT), emptyList(), "1.21", emptyList(), emptySet())
    assertTrue(issues.single() is PluginIssue.ApiTooNew)
  }

  @Test fun vanillaHasNoFolder() {
    assertEquals(null, Compat.folderFor(Engine.VANILLA))
    assertEquals("plugins", Compat.folderFor(Engine.PURPUR))
    assertEquals("mods", Compat.folderFor(Engine.FORGE))
  }

  @Test fun miniYamlPluginYml() {
    val y = MiniYaml.parse(
      """
      name: Vault # comment
      version: '1.7.3'
      api-version: "1.13"
      depend: [A, B]
      softdepend:
        - C
        - D
      commands:
        vault:
          description: x
      """.trimIndent(),
    )
    assertEquals("Vault", MiniYaml.str(y["name"]))
    assertEquals("1.7.3", MiniYaml.str(y["version"]))
    assertEquals("1.13", MiniYaml.str(y["api-version"]))
    assertEquals(listOf("A", "B"), MiniYaml.strings(y["depend"]))
    assertEquals(listOf("C", "D"), MiniYaml.strings(y["softdepend"]))
  }

  @Test fun miniYamlPaperDependencies() {
    val y = MiniYaml.parse(
      """
      name: X
      dependencies:
        server:
          Vault:
            load: BEFORE
            required: true
          Soft:
            load: AFTER
            required: false
      """.trimIndent(),
    )
    val server = (y["dependencies"] as Map<*, *>)["server"] as Map<*, *>
    assertEquals(setOf("Vault", "Soft"), server.keys)
    assertEquals("false", (server["Soft"] as Map<*, *>)["required"])
  }

  @Test fun miniToml() {
    val d = MiniToml.parse(
      """
      modLoader="javafml"
      [[mods]]
      modId="examplemod"
      version="1.2.3"
      [[dependencies.examplemod]]
      modId="minecraft"
      mandatory=true
      versionRange="[1.20.1,1.21)"
      [[dependencies.examplemod]]
      modId="forge"
      mandatory=true
      """.trimIndent(),
    )
    val mods = d.tables.filter { it.name == "mods" }
    assertEquals("examplemod", mods.single().str("modId"))
    val deps = d.tables.filter { it.name.startsWith("dependencies.") }
    assertEquals(2, deps.size)
    assertEquals("[1.20.1,1.21)", deps[0].str("versionRange"))
  }

  @Test fun plainTextStripsMarkdown() {
    val t = plainText("# Title\n![img](http://x/y.png)\nSome **bold** [link](http://z) <b>html</b>")
    assertFalse(t.contains("http"))
    assertFalse(t.contains("**"))
    assertTrue(t.contains("link"))
    assertTrue(t.contains("html"))
  }
}
