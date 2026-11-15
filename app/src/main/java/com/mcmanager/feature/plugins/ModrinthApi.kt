package com.mcmanager.feature.plugins

import com.mcmanager.core.net.HttpException
import com.mcmanager.core.net.getString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class MrSearchResponse(
  val hits: List<MrHit> = emptyList(),
  val offset: Int = 0,
  val limit: Int = 0,
  @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
data class MrHit(
  @SerialName("project_id") val projectId: String,
  val slug: String = "",
  val title: String = "",
  val description: String = "",
  val author: String = "",
  val downloads: Long = 0,
  @SerialName("icon_url") val iconUrl: String? = null,
)

@Serializable
data class MrProject(
  val id: String,
  val slug: String = "",
  val title: String = "",
  val description: String = "",
  val body: String = "",
  val downloads: Long = 0,
  @SerialName("icon_url") val iconUrl: String? = null,
)

@Serializable
data class MrHashes(val sha1: String? = null, val sha512: String? = null)

@Serializable
data class MrFile(
  val hashes: MrHashes = MrHashes(),
  val url: String,
  val filename: String,
  val primary: Boolean = false,
  val size: Long = 0,
)

@Serializable
data class MrDependency(
  @SerialName("version_id") val versionId: String? = null,
  @SerialName("project_id") val projectId: String? = null,
  @SerialName("dependency_type") val type: String = "optional",
)

@Serializable
data class MrVersion(
  val id: String,
  @SerialName("project_id") val projectId: String,
  val name: String = "",
  @SerialName("version_number") val versionNumber: String = "",
  @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
  val loaders: List<String> = emptyList(),
  val featured: Boolean = false,
  @SerialName("version_type") val versionType: String = "release",
  @SerialName("date_published") val datePublished: String = "",
  val files: List<MrFile> = emptyList(),
  val dependencies: List<MrDependency> = emptyList(),
) {
  /** The jar to download: primary if flagged, else the first .jar. Null if the version has no jar. */
  fun jarFile(): MrFile? =
    (files.firstOrNull { it.primary && it.filename.endsWith(".jar", true) }
      ?: files.firstOrNull { it.filename.endsWith(".jar", true) })
}

@Serializable
private data class UpdateBody(
  val hashes: List<String>,
  val algorithm: String,
  val loaders: List<String>,
  @SerialName("game_versions") val gameVersions: List<String>,
)

@Serializable
private data class HashesBody(val hashes: List<String>, val algorithm: String)

@Singleton
class ModrinthApi @Inject constructor(
  private val client: OkHttpClient,
  private val json: Json,
) {
  private val base = "https://api.modrinth.com/v2"

  private fun jsonArray(items: List<String>) = json.encodeToString(ListSerializer(String.serializer()), items)

  suspend fun search(
    query: String,
    projectType: String,
    categories: List<String>,
    mcVersion: String,
    index: String,
    offset: Int,
    limit: Int,
  ): MrSearchResponse {
    // Facets: outer list = AND, inner list = OR.
    val facets = buildList {
      add(listOf("project_type:$projectType"))
      if (categories.isNotEmpty()) add(categories.map { "categories:$it" })
      if (mcVersion.isNotBlank()) add(listOf("versions:$mcVersion"))
    }
    val url = "$base/search".toHttpUrl().newBuilder()
      .addQueryParameter("query", query)
      .addQueryParameter("facets", json.encodeToString(ListSerializer(ListSerializer(String.serializer())), facets))
      .addQueryParameter("index", index)
      .addQueryParameter("offset", offset.toString())
      .addQueryParameter("limit", limit.toString())
      .build().toString()
    return json.decodeFromString(client.getString(url))
  }

  suspend fun project(idOrSlug: String): MrProject =
    json.decodeFromString(client.getString("$base/project/$idOrSlug"))

  suspend fun version(id: String): MrVersion =
    json.decodeFromString(client.getString("$base/version/$id"))

  /** Versions of a project for the given loaders and game version, newest first. */
  suspend fun projectVersions(projectId: String, loaders: List<String>, mcVersion: String): List<MrVersion> {
    val url = "$base/project/$projectId/version".toHttpUrl().newBuilder()
      .addQueryParameter("loaders", jsonArray(loaders))
      .addQueryParameter("game_versions", jsonArray(listOf(mcVersion)))
      .build().toString()
    return json.decodeFromString(client.getString(url))
  }

  /** hash (sha1) -> version for files Modrinth knows. Unknown hashes are absent. */
  suspend fun versionsByHashes(sha1s: List<String>): Map<String, MrVersion> {
    if (sha1s.isEmpty()) return emptyMap()
    val body = json.encodeToString(HashesBody.serializer(), HashesBody(sha1s, "sha1"))
    val text = post("$base/version_files", body)
    return json.decodeFromString(MapSerializer(String.serializer(), MrVersion.serializer()), text)
  }

  /** hash (sha1) -> latest compatible version. */
  suspend fun latestForHashes(sha1s: List<String>, loaders: List<String>, mcVersion: String): Map<String, MrVersion> {
    if (sha1s.isEmpty()) return emptyMap()
    val body = json.encodeToString(UpdateBody.serializer(), UpdateBody(sha1s, "sha1", loaders, listOf(mcVersion)))
    val text = post("$base/version_files/update", body)
    return json.decodeFromString(MapSerializer(String.serializer(), MrVersion.serializer()), text)
  }

  private suspend fun post(url: String, body: String): String = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url)
      .post(body.toRequestBody("application/json".toMediaType()))
      .build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw HttpException(resp.code, url)
      resp.body.string()
    }
  }
}
