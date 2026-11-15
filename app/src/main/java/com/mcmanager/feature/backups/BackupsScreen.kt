package com.mcmanager.feature.backups

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.data.db.BackupEntity
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(serverId: Long, onBack: () -> Unit, vm: BackupsViewModel = hiltViewModel()) {
  LaunchedEffect(serverId) { vm.bind(serverId) }
  val server by vm.server.collectAsStateWithLifecycle()
  val backups by vm.backups.collectAsStateWithLifecycle()
  val runtime by vm.runtime.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  val snack = remember { SnackbarHostState() }
  LaunchedEffect(Unit) {
    vm.messages.collect { m ->
      snack.showSnackbar(if (m.arg != null) ctx.getString(m.res, m.arg) else ctx.getString(m.res))
    }
  }

  var showCreate by remember { mutableStateOf(false) }
  var restoreTarget by remember { mutableStateOf<BackupEntity?>(null) }
  var deleteTarget by remember { mutableStateOf<BackupEntity?>(null) }
  var exportTarget by remember { mutableStateOf<BackupEntity?>(null) }

  val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    val t = exportTarget
    exportTarget = null
    if (uri != null && t != null) vm.export(t, uri)
  }
  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) vm.import(uri)
  }

  val running = runtime.state != ServerState.STOPPED && runtime.state != ServerState.CRASHED
  val working = busy.second

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.ops_backups_title)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !working) {
            Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.ops_backup_import))
          }
        },
      )
    },
    floatingActionButton = {
      if (!working) {
        ExtendedFloatingActionButton(
          onClick = { showCreate = true },
          icon = { Icon(Icons.Filled.Add, contentDescription = null) },
          text = { Text(stringResource(R.string.ops_backup_create)) },
        )
      }
    },
    snackbarHost = { SnackbarHost(snack) },
  ) { padding ->
    LazyColumn(
      Modifier.padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (working) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ops_backup_working))
            busy.first?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
              ?: LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
      }
      item { ScheduleCard(server?.backupIntervalHours ?: 0, server?.backupKeep ?: 5, onChange = vm::setSchedule) }
      if (running) {
        item { Text(stringResource(R.string.ops_backup_running_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      }
      if (backups.isEmpty()) {
        item { EmptyState(title = stringResource(R.string.ops_backup_empty), modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)) }
      }
      items(backups, key = { it.id }) { b ->
        BackupRow(
          b, restoreEnabled = !running && !working,
          onRestore = { restoreTarget = b }, onDelete = { deleteTarget = b },
          onExport = {
            exportTarget = b
            exportLauncher.launch(java.io.File(b.file).name)
          },
        )
      }
    }
  }

  if (showCreate) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showCreate = false },
      title = { Text(stringResource(R.string.ops_backup_create)) },
      text = {
        OutlinedTextField(
          value = label, onValueChange = { label = it.take(40) }, singleLine = true,
          label = { Text(stringResource(R.string.ops_backup_label)) },
        )
      },
      confirmButton = {
        TextButton(onClick = { vm.create(label); showCreate = false }) { Text(stringResource(R.string.action_create)) }
      },
      dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
  restoreTarget?.let { b ->
    ConfirmDialog(
      title = stringResource(R.string.ops_backup_restore),
      text = stringResource(R.string.ops_backup_restore_confirm, b.label),
      onConfirm = { vm.restore(b); restoreTarget = null },
      onDismiss = { restoreTarget = null },
    )
  }
  deleteTarget?.let { b ->
    ConfirmDialog(
      title = stringResource(R.string.action_delete),
      text = stringResource(R.string.ops_backup_delete_confirm, b.label),
      onConfirm = { vm.delete(b); deleteTarget = null },
      onDismiss = { deleteTarget = null },
    )
  }
}

@Composable
private fun ScheduleCard(hours: Int, keep: Int, onChange: (Int, Int) -> Unit) {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.ops_schedule_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      Text(stringResource(R.string.ops_schedule_interval), style = MaterialTheme.typography.bodyMedium)
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 1, 3, 6, 12, 24).forEach { h ->
          FilterChip(
            selected = hours == h, onClick = { onChange(h, keep) },
            label = { Text(if (h == 0) stringResource(R.string.ops_schedule_off) else stringResource(R.string.ops_schedule_hours, h)) },
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ops_schedule_keep), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(hours, (keep - 1).coerceAtLeast(1)) }) { Icon(Icons.Filled.Remove, null) }
        Text(keep.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(hours, (keep + 1).coerceAtMost(100)) }) { Icon(Icons.Filled.Add, null) }
      }
      Text(stringResource(R.string.ops_schedule_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun BackupRow(
  b: BackupEntity, restoreEnabled: Boolean,
  onRestore: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit,
) {
  val ctx = LocalContext.current
  val date = remember(b.createdAt) { DateFormat.getMediumDateFormat(ctx).format(Date(b.createdAt)) + " " + DateFormat.getTimeFormat(ctx).format(Date(b.createdAt)) }
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(b.label, style = MaterialTheme.typography.titleMedium)
          Text(
            "$date  ·  ${Formatter.formatShortFileSize(ctx, b.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        AssistChip(
          onClick = {},
          label = { Text(stringResource(if (b.auto) R.string.ops_backup_auto else R.string.ops_backup_manual)) },
        )
      }
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onRestore, enabled = restoreEnabled) { Icon(Icons.Filled.Restore, stringResource(R.string.ops_backup_restore)) }
        IconButton(onClick = onExport) { Icon(Icons.Filled.FileDownload, stringResource(R.string.ops_backup_export)) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
      }
    }
  }
}
