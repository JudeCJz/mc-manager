package com.mcmanager.feature.players

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class WhitelistEntry(val uuid: String, val name: String)

data class OpEntry(val uuid: String, val name: String, val level: Int = 4, val bypassesPlayerLimit: Boolean = false)

data class BanEntry(
  val uuid: String,
  val name: String,
  val created: String = "",
  val source: String = "Server",
  val expires: String = "forever",
  val reason: String = "Banned by an operator.",
)

data class IpBanEntry(
  val ip: String,
  val created: String = "",
  val source: String = "Server",
  val expires: String = "forever",
  val reason: String = "Banned by an operator.",
)

object PlayerLists {
  private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

  fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))

  /** "069a79f444e94726a5befca90e38aaf5" -> "069a79f4-44e9-4726-a5be-fca90e38aaf5". Returns null if not 32 hex chars. */
  fun dashUuid(raw: String): String? {
    val s = raw.trim().replace("-", "").lowercase()
    if (!Regex("[0-9a-f]{32}").matches(s)) return null
    return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
  }

  fun looksLikeUuid(raw: String): Boolean = dashUuid(raw) != null

  fun banTimestamp(now: Long = System.currentTimeMillis()): String {
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(now))
  }

  private fun objects(text: String): List<JsonObject> {
    if (text.isBlank()) return emptyList()
    val el = try {
      Json.parseToJsonElement(text)
    } catch (e: Exception) {
      return emptyList()
    }
    return (el as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
  }

  private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

  fun parseWhitelist(text: String): List<WhitelistEntry> = objects(text).mapNotNull {
    val name = it.str("name") ?: return@mapNotNull null
    WhitelistEntry(it.str("uuid") ?: "", name)
  }

  fun parseOps(text: String): List<OpEntry> = objects(text).mapNotNull {
    val name = it.str("name") ?: return@mapNotNull null
    OpEntry(
      it.str("uuid") ?: "",
      name,
      (it["level"] as? JsonPrimitive)?.intOrNull ?: 4,
      (it["bypassesPlayerLimit"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
  }

  fun parseBans(text: String): List<BanEntry> = objects(text).mapNotNull {
    val name = it.str("name") ?: return@mapNotNull null
    BanEntry(
      it.str("uuid") ?: "", name, it.str("created") ?: "", it.str("source") ?: "",
      it.str("expires") ?: "forever", it.str("reason") ?: "",
    )
  }

  fun parseIpBans(text: String): List<IpBanEntry> = objects(text).mapNotNull {
    val ip = it.str("ip") ?: return@mapNotNull null
    IpBanEntry(ip, it.str("created") ?: "", it.str("source") ?: "", it.str("expires") ?: "forever", it.str("reason") ?: "")
  }

  private fun render(items: List<JsonElement>): String = pretty.encodeToString(JsonArray.serializer(), JsonArray(items))

  fun renderWhitelist(list: List<WhitelistEntry>): String = render(
    list.map { buildJsonObject { put("uuid", it.uuid); put("name", it.name) } },
  )

  fun renderOps(list: List<OpEntry>): String = render(
    list.map {
      buildJsonObject {
        put("uuid", it.uuid); put("name", it.name); put("level", it.level)
        put("bypassesPlayerLimit", it.bypassesPlayerLimit)
      }
    },
  )

  fun renderBans(list: List<BanEntry>): String = render(
    list.map {
      buildJsonObject {
        put("uuid", it.uuid); put("name", it.name); put("created", it.created)
        put("source", it.source); put("expires", it.expires); put("reason", it.reason)
      }
    },
  )

  fun renderIpBans(list: List<IpBanEntry>): String = render(
    list.map {
      buildJsonObject {
        put("ip", it.ip); put("created", it.created)
        put("source", it.source); put("expires", it.expires); put("reason", it.reason)
      }
    },
  )

  /** Parses the Mojang profile lookup response (`{"id":"...","name":"..."}`) into (dashedUuid, name). */
  fun parseMojangProfile(body: String): Pair<String, String>? {
    if (body.isBlank()) return null
    return try {
      val o = Json.parseToJsonElement(body).jsonObject
      val id = dashUuid(o["id"]?.jsonPrimitive?.content ?: return null) ?: return null
      id to (o["name"]?.jsonPrimitive?.content ?: return null)
    } catch (e: Exception) {
      null
    }
  }
}
