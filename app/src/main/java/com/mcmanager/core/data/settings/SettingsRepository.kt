package com.mcmanager.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

  private object Keys {
    val defaultXmxMb = intPreferencesKey("default_xmx_mb")
    val defaultXmsMb = intPreferencesKey("default_xms_mb")
    val defaultJvmPreset = stringPreferencesKey("default_jvm_preset")
    val defaultGc = stringPreferencesKey("default_gc")
    val globalJavaPath = stringPreferencesKey("global_java_path")
    val autoRestartOnCrash = booleanPreferencesKey("auto_restart_on_crash")
    val autoBackupHours = intPreferencesKey("auto_backup_hours")
    val theme = stringPreferencesKey("theme")
    val language = stringPreferencesKey("language")
    val storage = stringPreferencesKey("storage")
    val eulaConfirmedOnce = booleanPreferencesKey("eula_confirmed_once")
    val keepScreenAwakeOnConsole = booleanPreferencesKey("keep_screen_awake_on_console")
    val consoleMaxLines = intPreferencesKey("console_max_lines")
    val playitEnabled = booleanPreferencesKey("playit_enabled")
    val ngrokEnabled = booleanPreferencesKey("ngrok_enabled")
    val ngrokAuthToken = stringPreferencesKey("ngrok_auth_token")
    val customPublicAddress = stringPreferencesKey("custom_public_address")
  }

  val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

  suspend fun update(transform: (AppSettings) -> AppSettings) {
    store.edit { prefs ->
      val new = transform(prefs.toSettings())
      prefs[Keys.defaultXmxMb] = new.defaultXmxMb
      prefs[Keys.defaultXmsMb] = new.defaultXmsMb
      prefs[Keys.defaultJvmPreset] = new.defaultJvmPreset
      prefs[Keys.defaultGc] = new.defaultGc
      if (new.globalJavaPath != null) prefs[Keys.globalJavaPath] = new.globalJavaPath
      else prefs.remove(Keys.globalJavaPath)
      prefs[Keys.autoRestartOnCrash] = new.autoRestartOnCrash
      prefs[Keys.autoBackupHours] = new.autoBackupHours
      prefs[Keys.theme] = new.theme.name
      prefs[Keys.language] = new.language
      prefs[Keys.storage] = new.storage.name
      prefs[Keys.eulaConfirmedOnce] = new.eulaConfirmedOnce
      prefs[Keys.keepScreenAwakeOnConsole] = new.keepScreenAwakeOnConsole
      prefs[Keys.consoleMaxLines] = new.consoleMaxLines
      prefs[Keys.playitEnabled] = new.playitEnabled
      prefs[Keys.ngrokEnabled] = new.ngrokEnabled
      prefs[Keys.ngrokAuthToken] = new.ngrokAuthToken
      prefs[Keys.customPublicAddress] = new.customPublicAddress
    }
  }

  private fun Preferences.toSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
      defaultXmxMb = this[Keys.defaultXmxMb] ?: d.defaultXmxMb,
      defaultXmsMb = this[Keys.defaultXmsMb] ?: d.defaultXmsMb,
      defaultJvmPreset = this[Keys.defaultJvmPreset] ?: d.defaultJvmPreset,
      defaultGc = this[Keys.defaultGc] ?: d.defaultGc,
      globalJavaPath = this[Keys.globalJavaPath],
      autoRestartOnCrash = this[Keys.autoRestartOnCrash] ?: d.autoRestartOnCrash,
      autoBackupHours = this[Keys.autoBackupHours] ?: d.autoBackupHours,
      theme = enumOrDefault(this[Keys.theme], d.theme),
      language = this[Keys.language] ?: d.language,
      storage = enumOrDefault(this[Keys.storage], d.storage),
      eulaConfirmedOnce = this[Keys.eulaConfirmedOnce] ?: d.eulaConfirmedOnce,
      keepScreenAwakeOnConsole = this[Keys.keepScreenAwakeOnConsole] ?: d.keepScreenAwakeOnConsole,
      consoleMaxLines = this[Keys.consoleMaxLines] ?: d.consoleMaxLines,
      playitEnabled = this[Keys.playitEnabled] ?: d.playitEnabled,
      ngrokEnabled = this[Keys.ngrokEnabled] ?: d.ngrokEnabled,
      ngrokAuthToken = this[Keys.ngrokAuthToken] ?: d.ngrokAuthToken,
      customPublicAddress = this[Keys.customPublicAddress] ?: d.customPublicAddress,
    )
  }

  private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
}
