package com.mcmanager.core.ui.nav

import android.net.Uri

/** Route patterns (used in NavHost) and builders (used to navigate). Paths are URI-encoded by the builders. */
object Routes {
  const val ARG_ID = "id"
  const val ARG_PATH = "path"
  const val ARG_FILE = "file"

  const val DASHBOARD = "dashboard"
  const val CREATE = "create"
  const val SETTINGS = "settings"

  const val SERVER = "server/{$ARG_ID}"
  const val CONSOLE = "server/{$ARG_ID}/console"
  const val LOGS = "server/{$ARG_ID}/logs"
  const val FILES = "server/{$ARG_ID}/files?$ARG_PATH={$ARG_PATH}"
  const val EDIT = "server/{$ARG_ID}/edit?$ARG_PATH={$ARG_PATH}"
  const val PROPERTIES = "server/{$ARG_ID}/properties"
  const val YAML = "server/{$ARG_ID}/yaml?$ARG_FILE={$ARG_FILE}"
  const val PLAYERS = "server/{$ARG_ID}/players"
  const val PLUGINS = "server/{$ARG_ID}/plugins"
  const val VERSIONS = "server/{$ARG_ID}/versions"
  const val BACKUPS = "server/{$ARG_ID}/backups"
  const val NETWORK = "server/{$ARG_ID}/network"
  const val SERVER_SETTINGS = "server/{$ARG_ID}/settings"

  fun server(id: Long) = "server/$id"
  fun console(id: Long) = "server/$id/console"
  fun logs(id: Long) = "server/$id/logs"
  fun files(id: Long, path: String = "") = "server/$id/files?$ARG_PATH=${Uri.encode(path)}"
  fun edit(id: Long, path: String) = "server/$id/edit?$ARG_PATH=${Uri.encode(path)}"
  fun properties(id: Long) = "server/$id/properties"
  fun yaml(id: Long, file: String) = "server/$id/yaml?$ARG_FILE=${Uri.encode(file)}"
  fun players(id: Long) = "server/$id/players"
  fun plugins(id: Long) = "server/$id/plugins"
  fun versions(id: Long) = "server/$id/versions"
  fun backups(id: Long) = "server/$id/backups"
  fun network(id: Long) = "server/$id/network"
  fun serverSettings(id: Long) = "server/$id/settings"
}
