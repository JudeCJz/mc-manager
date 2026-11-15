package com.mcmanager.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.data.settings.AppSettings
import com.mcmanager.core.data.settings.StorageLocation
import com.mcmanager.core.data.settings.ThemeMode
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.feature.server.DropdownField
import com.mcmanager.feature.server.RamSlider
import com.mcmanager.feature.server.gcOptionPairs
import com.mcmanager.feature.server.jvmPresetOptions
import com.mcmanager.feature.server.maxXmxFor
import com.mcmanager.feature.server.rememberBatteryIgnored
import com.mcmanager.feature.server.requestIgnoreBatteryOptimizations
import androidx.compose.ui.platform.LocalContext

private val JavaDownloads = listOf(17, 21, 25)
private val ConsoleLineOptions = listOf(1000, 2000, 5000, 10000)
private val BackupHourOptions = listOf(0, 1, 3, 6, 12, 24)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
  val vm: SettingsViewModel = hiltViewModel()
  val settings by vm.settings.collectAsStateWithLifecycle()
  val runtimes by vm.runtimes.collectAsStateWithLifecycle()
  val downloads by vm.downloads.collectAsStateWithLifecycle()
  val error by vm.error.collectAsStateWithLifecycle()
  val batteryIgnored by rememberBatteryIgnored()
  val context = LocalContext.current

  Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
    val s = settings
    if (s == null) {
      LoadingBox(Modifier.fillMaxSize().padding(padding))
      return@Scaffold
    }
    val presets = jvmPresetOptions()
    val maxXmx = maxXmxFor(vm.totalRamMb)
    var javaPath by remember { mutableStateOf(s.globalJavaPath.orEmpty()) }
    val hourOptions = BackupHourOptions.map { h ->
      h.toString() to if (h == 0) stringResource(R.string.ss_backup_off) else stringResource(R.string.ss_backup_every, h)
    }

    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // ---- Defaults for new servers ----
      Header(stringResource(R.string.set_defaults))
      RamSlider(stringResource(R.string.create_xmx), s.defaultXmxMb.coerceIn(512, maxXmx), 512, maxXmx, { v ->
        vm.update { it.copy(defaultXmxMb = v, defaultXmsMb = minOf(it.defaultXmsMb, v)) }
      })
      RamSlider(stringResource(R.string.create_xms), s.defaultXmsMb.coerceAtMost(s.defaultXmxMb), 256, s.defaultXmxMb.coerceIn(512, maxXmx), { v ->
        vm.update { it.copy(defaultXmsMb = minOf(v, it.defaultXmxMb)) }
      })
      DropdownField(
        label = stringResource(R.string.create_jvm_preset),
        selectedLabel = presets.firstOrNull { it.first == s.defaultJvmPreset }?.second ?: s.defaultJvmPreset,
        options = presets,
        onSelect = { v -> vm.update { it.copy(defaultJvmPreset = v) } },
      )
      DropdownField(
        label = stringResource(R.string.ss_gc),
        selectedLabel = s.defaultGc,
        options = gcOptionPairs(),
        onSelect = { v -> vm.update { it.copy(defaultGc = v) } },
      )
      DropdownField(
        label = stringResource(R.string.set_default_backup),
        selectedLabel = hourOptions.firstOrNull { it.first == s.autoBackupHours.toString() }?.second ?: s.autoBackupHours.toString(),
        options = hourOptions,
        onSelect = { v -> vm.update { it.copy(autoBackupHours = v.toInt()) } },
      )
      SwitchRow(stringResource(R.string.set_auto_restart), s.autoRestartOnCrash) { v -> vm.update { it.copy(autoRestartOnCrash = v) } }

      // ---- Java ----
      Header(stringResource(R.string.ss_java))
      OutlinedTextField(
        value = javaPath,
        onValueChange = { v ->
          javaPath = v
          vm.update { it.copy(globalJavaPath = v.trim().ifBlank { null }) }
        },
        label = { Text(stringResource(R.string.set_global_java)) },
        supportingText = { Text(stringResource(R.string.set_global_java_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      val majors = (JavaDownloads + runtimes.map { it.major }).distinct().sorted()
      majors.forEach { major ->
        val rt = runtimes.firstOrNull { it.major == major }
        val progress = downloads[major]
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("Java $major", style = MaterialTheme.typography.titleSmall)
                Text(
                  when {
                    progress != null -> progress.step.ifBlank { stringResource(R.string.set_downloading) }
                    rt != null -> rt.versionString.ifBlank { stringResource(R.string.set_installed) }
                    else -> stringResource(R.string.set_not_installed)
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (progress == null) {
                if (rt != null) {
                  IconButton(onClick = { vm.remove(major) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.set_remove_java, major))
                  }
                } else {
                  IconButton(onClick = { vm.download(major) }) {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.set_download_java, major))
                  }
                }
              }
            }
            if (progress != null) {
              val f = progress.fraction
              Column(Modifier.padding(end = 8.dp, bottom = 4.dp)) {
                if (f != null) LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }
      }
      error?.let {
        Text(it.ifBlank { stringResource(R.string.error_generic) }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      // ---- Appearance ----
      Header(stringResource(R.string.set_appearance))
      Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { m ->
          FilterChip(
            selected = s.theme == m,
            onClick = { vm.update { it.copy(theme = m) } },
            label = { Text(stringResource(themeLabel(m))) },
          )
        }
      }
      Text(stringResource(R.string.set_language), style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = s.language == "en", onClick = { vm.update { it.copy(language = "en") } }, label = { Text("English") })
        FilterChip(selected = s.language == "hi", onClick = { vm.update { it.copy(language = "hi") } }, label = { Text("हिन्दी") })
      }

      // ---- Storage ----
      Header(stringResource(R.string.set_storage))
      StorageOption(StorageLocation.INTERNAL, s, R.string.set_storage_internal, R.string.set_storage_internal_desc) { v ->
        vm.update { it.copy(storage = v) }
      }
      StorageOption(StorageLocation.EXTERNAL_APP, s, R.string.set_storage_external, R.string.set_storage_external_desc) { v ->
        vm.update { it.copy(storage = v) }
      }
      Text(stringResource(R.string.set_storage_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

      // ---- Console ----
      Header(stringResource(R.string.set_console))
      Text(stringResource(R.string.set_console_lines), style = MaterialTheme.typography.bodyMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConsoleLineOptions.forEach { n ->
          FilterChip(selected = s.consoleMaxLines == n, onClick = { vm.update { it.copy(consoleMaxLines = n) } }, label = { Text(n.toString()) })
        }
      }
      SwitchRow(stringResource(R.string.set_keep_awake), s.keepScreenAwakeOnConsole) { v -> vm.update { it.copy(keepScreenAwakeOnConsole = v) } }

      // ---- Battery ----
      Header(stringResource(R.string.set_battery))
      Text(
        stringResource(if (batteryIgnored) R.string.set_battery_ok else R.string.set_battery_restricted),
        style = MaterialTheme.typography.bodyMedium,
      )
      if (!batteryIgnored) {
        Button(onClick = { requestIgnoreBatteryOptimizations(context) }) { Text(stringResource(R.string.battery_allow)) }
      }

      // ---- Phone tuning ----
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.set_tuning_title), style = MaterialTheme.typography.titleSmall)
          Text(stringResource(R.string.set_tuning_text), style = MaterialTheme.typography.bodySmall)
        }
      }

      // ---- About ----
      Header(stringResource(R.string.set_about))
      Text(stringResource(R.string.set_version, vm.versionName), style = MaterialTheme.typography.bodyMedium)
      Text(stringResource(R.string.set_no_telemetry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

private fun themeLabel(m: ThemeMode): Int = when (m) {
  ThemeMode.SYSTEM -> R.string.theme_system
  ThemeMode.LIGHT -> R.string.theme_light
  ThemeMode.DARK -> R.string.theme_dark
  ThemeMode.AMOLED -> R.string.theme_amoled
}

@Composable
private fun Header(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun StorageOption(
  value: StorageLocation,
  s: AppSettings,
  title: Int,
  desc: Int,
  onSelect: (StorageLocation) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().selectable(selected = s.storage == value, role = Role.RadioButton, onClick = { onSelect(value) }),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = s.storage == value, onClick = null)
    Column(Modifier.padding(start = 12.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
      Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
