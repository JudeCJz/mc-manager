package com.mcmanager.feature.backups

/** Pure include/exclude rules for backup archives (paths relative to the server dir, '/' separated). */
object BackupFilter {
  private val configExts = setOf("properties", "yml", "yaml", "json", "toml", "txt")
  private val excludedDirs = setOf("logs", "cache", "libraries", "versions", "crash-reports", ".mcmanager")
  private val containerDirs = setOf("config", "plugins", "mods")

  fun worldNames(level: String): List<String> = listOf(level, "${level}_nether", "${level}_the_end")

  /**
   * @param rel path relative to the server directory
   * @param launchJar the server's launchSpec (excluded when it is a plain jar path)
   */
  fun include(rel: String, isDir: Boolean, level: String, launchJar: String?): Boolean {
    val parts = rel.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return true
    if (parts.any { it in excludedDirs }) return false
    val name = parts.last()
    if (!isDir && (name.endsWith(".log") || name.endsWith(".lck") || name == "session.lock")) return false
    if (!launchJar.isNullOrBlank() && !launchJar.startsWith("@") &&
      rel.trim('/') == launchJar.trim('/')
    ) return false
    val top = parts[0]
    if (parts.size == 1) {
      return if (isDir) top in worldNames(level) || top in containerDirs
      else name.substringAfterLast('.', "").lowercase() in configExts
    }
    return top in worldNames(level) || top in containerDirs
  }
}
