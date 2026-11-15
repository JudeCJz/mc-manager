package com.mcmanager

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.core.data.settings.AppSettings
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.ui.nav.AppNavHost
import com.mcmanager.core.ui.theme.MCManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

  @Inject lateinit var settingsRepository: SettingsRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val settings: AppSettings? by settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = null)
      val loaded = settings
      LaunchedEffect(loaded?.language) {
        val lang = loaded?.language ?: return@LaunchedEffect
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!current.startsWith(lang)) {
          AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
      }
      MCManagerTheme(mode = (loaded ?: AppSettings()).theme) {
        AppNavHost()
      }
    }
  }
}
