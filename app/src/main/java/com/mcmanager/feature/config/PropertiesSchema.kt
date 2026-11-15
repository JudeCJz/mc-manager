package com.mcmanager.feature.config

enum class PropType { BOOL, INT, ENUM, STRING }

enum class PropGroup { GAMEPLAY, WORLD, PLAYERS, NETWORK, ADVANCED }

data class PropDef(
  val key: String,
  val type: PropType,
  val default: String,
  val group: PropGroup,
  val desc: String,
  val min: Long? = null,
  val max: Long? = null,
  val options: List<String> = emptyList(),
  val secret: Boolean = false,
)

object PropertiesSchema {
  private val G = PropGroup.GAMEPLAY
  private val W = PropGroup.WORLD
  private val P = PropGroup.PLAYERS
  private val N = PropGroup.NETWORK
  private val A = PropGroup.ADVANCED

  private fun b(k: String, d: Boolean, g: PropGroup, desc: String) = PropDef(k, PropType.BOOL, d.toString(), g, desc)
  private fun i(k: String, d: Long, g: PropGroup, desc: String, min: Long? = null, max: Long? = null) =
    PropDef(k, PropType.INT, d.toString(), g, desc, min, max)
  private fun e(k: String, d: String, g: PropGroup, desc: String, opts: List<String>) =
    PropDef(k, PropType.ENUM, d, g, desc, options = opts)
  private fun s(k: String, d: String, g: PropGroup, desc: String, secret: Boolean = false) =
    PropDef(k, PropType.STRING, d, g, desc, secret = secret)

  val defs: List<PropDef> = listOf(
    e("difficulty", "easy", G, "Game difficulty", listOf("peaceful", "easy", "normal", "hard")),
    e("gamemode", "survival", G, "Default game mode for new players", listOf("survival", "creative", "adventure", "spectator")),
    b("force-gamemode", false, G, "Force players into the default game mode on join"),
    b("hardcore", false, G, "Players are banned on death"),
    b("pvp", true, G, "Players can damage each other"),
    b("allow-flight", false, G, "Allow flying (prevents kick for flight hacks)"),
    b("enable-command-block", false, G, "Enable command blocks"),
    i("spawn-protection", 16, G, "Radius around spawn that only ops can edit", 0, 1000),
    b("allow-nether", true, G, "Allow travel to the Nether"),
    s("level-name", "world", W, "World folder name"),
    s("level-seed", "", W, "World seed (blank = random)"),
    e(
      "level-type", "minecraft:normal", W, "World type",
      listOf("minecraft:normal", "minecraft:flat", "minecraft:large_biomes", "minecraft:amplified", "minecraft:single_biome_surface"),
    ),
    b("generate-structures", true, W, "Generate villages, temples and so on"),
    i("max-world-size", 29999984, W, "Maximum world radius in blocks", 1, 29999984),
    i("view-distance", 10, W, "Chunks sent to each player (lower = faster)", 3, 32),
    i("simulation-distance", 10, W, "Chunks around players that are ticked", 3, 32),
    i("max-players", 20, P, "Maximum players online", 0, 100000),
    s("motd", "A Minecraft Server", P, "Message shown in the server list"),
    b("white-list", false, P, "Only whitelisted players can join"),
    b("enforce-whitelist", false, P, "Kick non-whitelisted players when the whitelist reloads"),
    b("online-mode", true, P, "Verify accounts with Mojang (off = offline UUIDs)"),
    i("op-permission-level", 4, P, "Permission level given to ops", 1, 4),
    i("player-idle-timeout", 0, P, "Kick idle players after N minutes (0 = never)", 0, 100000),
    b("hide-online-players", false, P, "Hide the player list in the server list"),
    b("log-ips", true, P, "Log player IP addresses"),
    b("enforce-secure-profile", true, P, "Require signed chat keys from Mojang"),
    s("server-ip", "", N, "IP to bind to (leave blank for all)"),
    i("server-port", 25565, N, "TCP port the server listens on", 1, 65535),
    b("enable-status", true, N, "Respond to server list pings"),
    b("enable-query", false, N, "Enable GameSpy4 query protocol"),
    i("query.port", 25565, N, "UDP port for query", 1, 65535),
    b("enable-rcon", false, N, "Enable remote console"),
    i("rcon.port", 25575, N, "Port for RCON", 1, 65535),
    s("rcon.password", "", N, "Password for RCON", secret = true),
    i("network-compression-threshold", 256, N, "Compress packets larger than N bytes (-1 = off)", -1, 1000000),
    i("rate-limit", 0, N, "Max packets per second per player (0 = off)", 0, 100000),
    b("prevent-proxy-connections", false, N, "Block VPN / proxy connections"),
    b("use-native-transport", true, N, "Optimised packet handling on Linux"),
    b("accepts-transfers", false, N, "Accept players transferred from other servers"),
    b("require-resource-pack", false, N, "Kick players who decline the resource pack"),
    s("resource-pack", "", N, "Resource pack download URL"),
    s("resource-pack-sha1", "", N, "SHA-1 of the resource pack"),
    i("max-tick-time", 60000, A, "Stop server if a tick takes longer than N ms (-1 = off)", -1, Int.MAX_VALUE.toLong()),
    i("entity-broadcast-range-percentage", 100, A, "Entity render distance percentage", 10, 1000),
    b("sync-chunk-writes", true, A, "Write chunks synchronously (safer, slower)"),
    b("broadcast-console-to-ops", true, A, "Show console output to ops"),
    i("pause-when-empty-seconds", 60, A, "Pause ticking after N seconds with no players (-1 = never)", -1, 1000000),
  )

  private val byKey = defs.associateBy { it.key }

  fun def(key: String): PropDef? = byKey[key]

  /** Returns an error message or null. */
  fun validate(def: PropDef, value: String): String? = when (def.type) {
    PropType.INT -> {
      val n = value.trim().toLongOrNull()
      when {
        n == null -> "Not a number"
        def.min != null && n < def.min -> "Minimum ${def.min}"
        def.max != null && n > def.max -> "Maximum ${def.max}"
        else -> null
      }
    }
    else -> null
  }

  fun defaultsFile(): PropertiesFile {
    val p = PropertiesFile.empty()
    for (d in defs) p.set(d.key, d.default)
    return p
  }
}
