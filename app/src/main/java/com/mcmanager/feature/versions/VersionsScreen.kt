package com.mcmanager.feature.versions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.Engine
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.core.ui.components.SectionHeader
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(serverId: Long, onBack: () -> Unit) {
  val vm: VersionsViewModel = hiltViewModel()
  val st by vm.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current

  LaunchedEffect(vm) {
    vm.messages.collect { m ->
      snackbar.showSnackbar(m.raw ?: context.getString(m.res, *m.args.toTypedArray()))
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.versions_title)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = vm::reload, enabled = st.server != null) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    val server = st.server
    if (server == null) {
      LoadingBox(Modifier.fillMaxSize().padding(padding))
    } else {
      LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item { CurrentCard(st) }

        if (!st.canChange) {
          item {
            Text(
              stringResource(R.string.versions_stop_first),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }

        if (st.checkingUpdate) {
          item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
              Text(stringResource(R.string.versions_checking_update), style = MaterialTheme.typography.bodyMedium)
            }
          }
        } else if (st.updateLabel != null && server.engine.supported) {
          item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
              Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Text(stringResource(R.string.versions_update_available), style = MaterialTheme.typography.titleSmall)
                  Text(st.updateLabel!!, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = vm::requestUpdate, enabled = st.canChange && !st.busy) {
                  Text(stringResource(R.string.action_update))
                }
              }
            }
          }
        } else {
          item {
            Text(
              stringResource(R.string.versions_up_to_date),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { SectionHeader(stringResource(R.string.versions_switch_header), Modifier.padding(horizontal = 0.dp)) }
        item {
          Text(
            stringResource(R.string.versions_engine_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        item {
          OutlinedTextField(
            value = st.search,
            onValueChange = vm::setSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.versions_search_hint)) },
          )
        }
        when {
          st.versionsLoading -> item { LoadingBox(Modifier.fillMaxWidth().padding(24.dp)) }
          st.versionsError != null -> item {
            Text(stringResource(R.string.versions_error, st.versionsError!!), color = MaterialTheme.colorScheme.error)
          }
          st.filtered.isEmpty() -> item { Text(stringResource(R.string.versions_none)) }
          else -> items(st.filtered, key = { it }) { v ->
            val current = v == server.mcVersion
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                v + if (current) "  (" + stringResource(R.string.versions_current_tag) + ")" else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
              )
              OutlinedButton(onClick = { vm.requestSwitch(v) }, enabled = st.canChange && !st.busy && !current) {
                Text(stringResource(R.string.versions_switch))
              }
            }
          }
        }

        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { SectionHeader(stringResource(R.string.versions_history_header), Modifier.padding(horizontal = 0.dp)) }
        if (st.history.isEmpty()) {
          item {
            Text(
              stringResource(R.string.versions_history_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          items(st.history, key = { it.id }) { e -> HistoryCard(e, enabled = st.canChange && !st.busy && (e.savedPath != null || e.launchSpec == null || e.launchSpec.startsWith("@")), onRollback = { vm.requestRollback(e) }) }
        }
      }
    }
  }

  // ---- dialogs ------------------------------------------------------------------------------

  st.pendingSwitch?.let { p ->
    AlertDialog(
      onDismissRequest = vm::cancelSwitch,
      title = {
        Text(
          if (p.isUpdate) stringResource(R.string.versions_confirm_update_title, p.mcVersion)
          else stringResource(R.string.versions_confirm_switch_title, p.mcVersion),
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.versions_confirm_text))
          if (p.downgrade) {
            Text(
              stringResource(R.string.versions_downgrade_warning),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          Text(stringResource(R.string.versions_plugins_warning), style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {
        Column(horizontalAlignment = Alignment.End) {
          TextButton(onClick = { vm.confirmSwitch(true) }) { Text(stringResource(R.string.versions_backup_and_switch)) }
          TextButton(onClick = { vm.confirmSwitch(false) }) { Text(stringResource(R.string.versions_switch_no_backup)) }
          TextButton(onClick = vm::cancelSwitch) { Text(stringResource(R.string.action_cancel)) }
        }
      },
    )
  }

  st.pendingRollback?.let { e ->
    AlertDialog(
      onDismissRequest = vm::cancelRollback,
      title = { Text(stringResource(R.string.versions_rollback_title, e.mcVersion)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.versions_rollback_text))
          if (e.backupId != null) {
            Text(stringResource(R.string.versions_rollback_backup, e.backupLabel ?: ""), style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = {
        Column(horizontalAlignment = Alignment.End) {
          if (e.backupId != null) {
            TextButton(onClick = { vm.confirmRollback(true) }) { Text(stringResource(R.string.versions_rollback_with_backup)) }
          }
          TextButton(onClick = { vm.confirmRollback(false) }) { Text(stringResource(R.string.versions_rollback_only)) }
          TextButton(onClick = vm::cancelRollback) { Text(stringResource(R.string.action_cancel)) }
        }
      },
    )
  }

  if (st.busy) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text(stringResource(R.string.versions_working)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val step = when (st.busyStep) {
            "backup" -> stringResource(R.string.versions_step_backup)
            "save" -> stringResource(R.string.versions_step_save)
            "restore" -> stringResource(R.string.versions_step_restore)
            "install" -> stringResource(R.string.versions_step_install)
            null -> stringResource(R.string.loading)
            else -> st.busyStep!!
          }
          Text(step, style = MaterialTheme.typography.bodyMedium)
          val f = st.busyFraction
          if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
          else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      },
      confirmButton = {},
    )
  }
}

@Composable
private fun CurrentCard(st: VersionsUiState) {
  val s = st.server ?: return
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(R.string.versions_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      Text("${s.engine.label} ${s.mcVersion}", style = MaterialTheme.typography.headlineSmall)
      InfoRow(stringResource(R.string.versions_build), s.engineBuild ?: stringResource(R.string.versions_unknown))
      InfoRow(stringResource(R.string.versions_java), "Java ${s.javaMajor}")
      InfoRow(stringResource(R.string.versions_launch), s.launchSpec ?: stringResource(R.string.versions_not_installed))
      if (s.engine == Engine.SPIGOT) Text(stringResource(R.string.versions_spigot_note), style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun HistoryCard(e: HistoryEntry, enabled: Boolean, onRollback: () -> Unit) {
  val date = remember(e.date) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.date)) }
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Text("${e.engine.lowercase().replaceFirstChar { it.uppercase() }} ${e.mcVersion}", style = MaterialTheme.typography.titleSmall)
        Text(
          listOfNotNull(
            e.build?.let { stringResource(R.string.versions_build_n, it) },
            date,
            if (e.savedPath == null && e.launchSpec?.startsWith("@") != true) stringResource(R.string.versions_no_saved_jar) else null,
            if (e.backupId != null) stringResource(R.string.versions_has_backup) else null,
          ).joinToString("  ·  "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(onClick = onRollback, enabled = enabled) { Text(stringResource(R.string.versions_roll_back)) }
    }
  }
}
