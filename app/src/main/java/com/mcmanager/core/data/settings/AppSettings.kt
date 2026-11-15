package com.mcmanager.core.data.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class StorageLocation { INTERNAL, EXTERNAL_APP }

data class AppSettings(
  val defaultXmxMb: Int = 3072,
  val defaultXmsMb: Int = 1024,
  val defaultJvmPreset: String = "aikar",
  val defaultGc: String = "G1",
  val globalJavaPath: String? = null, // null = managed runtimes
  val autoRestartOnCrash: Boolean = true,
  val autoBackupHours: Int = 0, // default for new servers
  val theme: ThemeMode = ThemeMode.SYSTEM,
  val language: String = "en", // "en" | "hi"
  val storage: StorageLocation = StorageLocation.INTERNAL,
  val eulaConfirmedOnce: Boolean = false,
  val keepScreenAwakeOnConsole: Boolean = false,
  val consoleMaxLines: Int = 5000,
  val playitEnabled: Boolean = false,
  val ngrokEnabled: Boolean = false,
  val ngrokAuthToken: String = "",
  val customPublicAddress: String = "",
)
