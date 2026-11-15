package com.mcmanager.feature.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.LoadingBox
import androidx.compose.ui.text.style.TextOverflow

private val JavaMajors = listOf(8, 17, 21, 25)
private val BackupHourOptions = listOf(0, 1, 3, 6, 12, 24)
private val BackupKeepOptions = listOf(1, 3, 5, 10, 20)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerSettingsScreen(serverId: Long, onBack: () -> Unit) {
  val vm: ServerSettingsViewModel = hiltViewModel()
  val ui by vm.ui.collectAsStateWithLifecycle()
  val state by vm.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val savedMsg = stringResource(R.string.ss_saved)
  var confirmDelete by remember { mutableStateOf(false) }

  LaunchedEffect(ui.savedTick) { if (ui.savedTick > 0) snackbar.showSnackbar(savedMsg) }
  // After deleting: leave; the hub notices the missing server and leaves too, landing on the dashboard.
  LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }
  LaunchedEffect(ui.missing) { if (ui.missing) onBack() }

  val form = ui.form
  Scaffold(
    topBar = { TopAppBar(title = { Text(stringResource(R.string.tile_server_settings)) }, navigationIcon = { BackButton(onBack) }) },
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      if (form != null) {
        Surface(tonalElevation = 3.dp) {
          Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = vm::save, enabled = form.valid) { Text(stringResource(R.string.action_save)) }
          }
        }
      }
    },
  ) { padding ->
    if (form == null) {
      LoadingBox(Modifier.fillMaxSize().padding(padding))
      return@Scaffold
    }
    val stopped = state == ServerState.STOPPED || state == ServerState.CRASHED
    val maxXmx = maxXmxFor(ui.totalRamMb)
    val presets = jvmPresetOptions()
    val hourOptions = BackupHourOptions.map { h ->
      h.toString() to if (h == 0) stringResource(R.string.ss_backup_off) else stringResource(R.string.ss_backup_every, h)
    }
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!stopped) {
        Text(stringResource(R.string.ss_restart_needed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
      }

      Header(stringResource(R.string.ss_general))
      OutlinedTextField(
        value = form.name,
        onValueChange = { v -> vm.edit { it.copy(name = v.take(40)) } },
        label = { Text(stringResource(R.string.create_name)) },
        singleLine = true,
        isError = form.name.isBlank(),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = form.port,
        onValueChange = { v -> vm.edit { it.copy(port = v.filter(Char::isDigit).take(5)) } },
        label = { Text(stringResource(R.string.create_port)) },
        singleLine = true,
        enabled = stopped,
        isError = form.portNumber == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
          Text(stringResource(if (!stopped) R.string.ss_port_locked else if (form.portNumber == null) R.string.create_port_invalid else R.string.ss_port_hint))
        },
        modifier = Modifier.fillMaxWidth(),
      )

      Header(stringResource(R.string.ss_memory))
      RamSlider(stringResource(R.string.create_xmx), form.xmx, 512, maxXmx, vm::setXmx)
      RamSlider(stringResource(R.string.create_xms), form.xms, 256, form.xmx, vm::setXms)
      RamWarning(ramLevel(ui.otherRunningXmxMb + form.xmx, ui.totalRamMb))

      Header(stringResource(R.string.ss_jvm))
      DropdownField(
        label = stringResource(R.string.create_jvm_preset),
        selectedLabel = presets.firstOrNull { it.first == form.jvmPreset }?.second ?: form.jvmPreset,
        options = presets,
        onSelect = { v -> vm.edit { it.copy(jvmPreset = v) } },
      )
      DropdownField(
        label = stringResource(R.string.ss_gc),
        selectedLabel = form.gc,
        options = gcOptionPairs(),
        onSelect = { v -> vm.edit { it.copy(gc = v) } },
      )
      OutlinedTextField(
        value = form.extraArgs,
        onValueChange = { v -> vm.edit { it.copy(extraArgs = v) } },
        label = { Text(stringResource(R.string.ss_extra_args)) },
        modifier = Modifier.fillMaxWidth(),
      )

      Header(stringResource(R.string.ss_java))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ss_java_custom), Modifier.weight(1f))
        Switch(checked = form.customJava, onCheckedChange = { v -> vm.edit { it.copy(customJava = v) } })
      }
      if (form.customJava) {
        OutlinedTextField(
          value = form.javaPath,
          onValueChange = { v -> vm.edit { it.copy(javaPath = v) } },
          label = { Text(stringResource(R.string.ss_java_path)) },
          singleLine = true,
          isError = form.javaPath.isBlank(),
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Text(stringResource(R.string.ss_java_managed, form.javaMajor), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          JavaMajors.forEach { m ->
            FilterChip(
              selected = form.javaMajor == m,
              onClick = { vm.edit { it.copy(javaMajor = m) } },
              label = { Text("Java $m") },
            )
          }
        }
      }

      Header(stringResource(R.string.ss_behaviour))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ss_auto_restart), Modifier.weight(1f))
        Switch(checked = form.autoRestart, onCheckedChange = { v -> vm.edit { it.copy(autoRestart = v) } })
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ss_eula), Modifier.weight(1f))
        Switch(checked = form.eula, onCheckedChange = { v -> vm.edit { it.copy(eula = v) } })
      }

      Header(stringResource(R.string.ss_backups))
      DropdownField(
        label = stringResource(R.string.ss_backup_interval),
        selectedLabel = hourOptions.firstOrNull { it.first == form.backupHours.toString() }?.second ?: form.backupHours.toString(),
        options = hourOptions,
        onSelect = { v -> vm.edit { it.copy(backupHours = v.toInt()) } },
      )
      DropdownField(
        label = stringResource(R.string.ss_backup_keep),
        selectedLabel = form.backupKeep.toString(),
        options = BackupKeepOptions.map { it.toString() to it.toString() },
        onSelect = { v -> vm.edit { it.copy(backupKeep = v.toInt()) } },
        enabled = form.backupHours > 0,
      )

      HorizontalDivider(Modifier.padding(top = 8.dp))
      Header(stringResource(R.string.ss_danger))
      if (!stopped) Text(stringResource(R.string.ss_delete_stop_first), style = MaterialTheme.typography.bodySmall)
      OutlinedButton(
        onClick = { confirmDelete = true },
        enabled = stopped,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) { Text(stringResource(R.string.ss_delete)) }
      Text("", Modifier.padding(bottom = 8.dp))
    }

    if (confirmDelete) {
      var deleteFiles by remember { mutableStateOf(false) }
      AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.ss_delete_title, form.name)) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ss_delete_text))
            Row(Modifier.fillMaxWidth().clickable { deleteFiles = !deleteFiles }, verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
              Text(stringResource(R.string.ss_delete_files))
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { confirmDelete = false; vm.delete(deleteFiles) }) {
            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
      )
    }
  }
}

@Composable
private fun Header(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
