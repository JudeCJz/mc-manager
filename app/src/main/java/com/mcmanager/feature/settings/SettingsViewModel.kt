package com.mcmanager.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcmanager.core.data.settings.AppSettings
import com.mcmanager.core.data.settings.SettingsRepository
import com.mcmanager.core.engine.JavaRuntimeManager
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.core.model.JavaRuntime
import com.mcmanager.core.util.RamUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repo: SettingsRepository,
  private val javaManager: JavaRuntimeManager,
) : ViewModel() {

  val settings: StateFlow<AppSettings?> =
    repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  private val _runtimes = MutableStateFlow<List<JavaRuntime>>(emptyList())
  val runtimes: StateFlow<List<JavaRuntime>> = _runtimes.asStateFlow()

  /** Download progress by Java major. */
  private val _downloads = MutableStateFlow<Map<Int, InstallProgress>>(emptyMap())
  val downloads: StateFlow<Map<Int, InstallProgress>> = _downloads.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  val totalRamMb: Int = RamUtils.totalRamMb(context)

  val versionName: String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
  } catch (_: Exception) {
    ""
  }

  init {
    refreshRuntimes()
  }

  fun refreshRuntimes() {
    viewModelScope.launch {
      _runtimes.value = withContext(Dispatchers.IO) { runCatching { javaManager.installed() }.getOrDefault(emptyList()) }
    }
  }

  fun update(transform: (AppSettings) -> AppSettings) {
    viewModelScope.launch { repo.update(transform) }
  }

  fun download(major: Int) {
    if (major in _downloads.value) return
    viewModelScope.launch {
      _error.value = null
      _downloads.update { it + (major to InstallProgress("", null)) }
      try {
        javaManager.ensure(major) { p -> _downloads.update { it + (major to p) } }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        _error.value = e.message ?: e.javaClass.simpleName
      } finally {
        _downloads.update { it - major }
        refreshRuntimes()
      }
    }
  }

  fun remove(major: Int) {
    viewModelScope.launch {
      runCatching { javaManager.remove(major) }.exceptionOrNull()?.let { _error.value = it.message.orEmpty() }
      refreshRuntimes()
    }
  }

  fun dismissError() {
    _error.value = null
  }
}
