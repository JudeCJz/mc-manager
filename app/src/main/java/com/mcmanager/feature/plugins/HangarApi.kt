package com.mcmanager.feature.plugins

import com.mcmanager.core.net.getString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Serializable
data class HgPagination(val count: Int = 0, val limit: Int = 0, val offset: Int = 0)

@Serializable
data class HgNamespace(val owner: String = "", val slug: String = "")

@Serializable
data class HgStats(val downloads: Long = 0, val stars: Long = 0)

@Serializable
data class HgProject(
  val name: String = "",
  val namespace: HgNamespace = HgNamespace(),
  val stats: HgStats = HgStats(),
  val description: String = "",
  val avatarUrl: String? = null,
)

@Serializable
data class HgSearch(val pagination: HgPagination = HgPagination(), val result: List<HgProject> = emptyList())

@Serializable
data class HgFileInfo(val name: String = "", val sizeBytes: Long = 0, val sha256Hash: String? = null)

@Serializable
data class HgDownload(
  val fileInfo: HgFileInfo? = null,
  val externalUrl: String? = null,
  val downloadUrl: String? = null,
)

@Serializable
data class HgChannel(val name: String = "")

@Serializable
data class HgPluginDep(val name: String = "", val required: Boolean = false, val externalUrl: String? = null)

@Serializable
data class HgVersion(
  val id: Long = 0,
  val name: String = "",
  val createdAt: String = "",
  val channel: HgChannel = HgChannel(),
  val downloads: Map<String, HgDownload> = emptyMap(),
  val pluginDependencies: Map<String, List<HgPluginDep>> = emptyMap(),
)

@Serializable
data class HgVersions(val pagination: HgPagination = HgPagination(), val result: List<HgVersion> = emptyList())

@Singleton
class HangarApi @Inject constructor(
  private val client: OkHttpClient,
  private val json: Json,
) {
  private val base = "https://hangar.papermc.io/api/v1"

  /** [sort]: null (default ranking), "-downloads", "-updated", "-newest", "-stars". */
  suspend fun search(query: String, platform: String, mcVersion: String, sort: String?, offset: Int, limit: Int): HgSearch {
    val url = "$base/projects".toHttpUrl().newBuilder()
      .addQueryParameter("q", query)
      .addQueryParameter("platform", platform)
      .addQueryParameter("version", mcVersion)
      .addQueryParameter("limit", limit.toString())
      .addQueryParameter("offset", offset.toString())
      .apply { if (sort != null) addQueryParameter("sort", sort) }
      .build().toString()
    return json.decodeFromString(client.getString(url))
  }

  suspend fun project(slug: String): HgProject =
    json.decodeFromString(client.getString("$base/projects/$slug"))

  suspend fun versions(slug: String, platform: String, mcVersion: String, limit: Int = 25): List<HgVersion> {
    val url = "$base/projects/$slug/versions".toHttpUrl().newBuilder()
      .addQueryParameter("platform", platform)
      .addQueryParameter("platformVersion", mcVersion)
      .addQueryParameter("limit", limit.toString())
      .addQueryParameter("offset", "0")
      .build().toString()
    return json.decodeFromString<HgVersions>(client.getString(url)).result
  }
}
