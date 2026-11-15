package com.mcmanager.feature.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.core.ui.nav.Routes
import kotlinx.coroutines.launch

private data class Tile(val icon: ImageVector, val label: String, val route: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerHubScreen(serverId: Long, navigate: (String) -> Unit, onBack: () -> Unit) {
  val vm: ServerHubViewModel = hiltViewModel()
  val ui by vm.ui.collectAsStateWithLifecycle()
  val pendingStart by vm.pendingStart.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val generic = stringResource(R.string.error_generic)
  val copied = stringResource(R.string.hub_copied)
  var confirmKill by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it.ifBlank { generic }) } }
  // Server deleted (e.g. from its settings screen): leave.
  LaunchedEffect(ui.loaded, ui.server) { if (ui.loaded && ui.server == null) onBack() }

  val server = ui.server
  Scaffold(
    topBar = {
      TopAppBar(title = { Text(server?.name.orEmpty()) }, navigationIcon = { BackButton(onBack) })
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    if (server == null) {
      LoadingBox(Modifier.fillMaxSize().padding(padding))
      return@Scaffold
    }
    val rt = ui.runtime
    val id = server.id
    val tiles = buildList {
      add(Tile(Icons.Filled.Terminal, stringResource(R.string.tile_console), Routes.console(id)))
      add(Tile(Icons.Filled.Article, stringResource(R.string.tile_logs), Routes.logs(id)))
      add(Tile(Icons.Filled.Folder, stringResource(R.string.tile_files), Routes.files(id)))
      add(Tile(Icons.Filled.Tune, stringResource(R.string.tile_properties), Routes.properties(id)))
      ui.yamlFiles.forEach { f -> add(Tile(Icons.Filled.Code, f.substringAfterLast('/'), Routes.yaml(id, f))) }
      add(Tile(Icons.Filled.People, stringResource(R.string.tile_players), Routes.players(id)))
      add(
        Tile(
          Icons.Filled.Extension,
          stringResource(if (server.engine.isModLoader) R.string.tile_mods else R.string.tile_plugins),
          Routes.plugins(id),
        ),
      )
      add(Tile(Icons.Filled.Update, stringResource(R.string.tile_versions), Routes.versions(id)))
      add(Tile(Icons.Filled.Backup, stringResource(R.string.tile_backups), Routes.backups(id)))
      add(Tile(Icons.Filled.Wifi, stringResource(R.string.tile_network), Routes.network(id)))
      add(Tile(Icons.Filled.Settings, stringResource(R.string.tile_server_settings), Routes.serverSettings(id)))
    }

    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("${server.engine.label} ${server.mcVersion}", style = MaterialTheme.typography.titleMedium)
                Text(
                  stringResource(R.string.hub_port_ram, server.port, formatMb(server.xmxMb)),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              StateChip(rt.state)
            }
            if (rt.state == ServerState.INSTALLING) {
              val p = rt.progress
              if (p != null) LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
              else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!rt.message.isNullOrBlank()) {
              Text(
                rt.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (rt.state == ServerState.CRASHED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (rt.state == ServerState.RUNNING || rt.state == ServerState.STARTING || rt.state == ServerState.STOPPING) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HubStat(stringResource(R.string.stat_players), "${rt.onlinePlayers.size}/${rt.maxPlayers ?: "?"}")
                HubStat(stringResource(R.string.stat_tps), formatTps(rt.tps))
                HubStat(stringResource(R.string.stat_cpu), formatCpu(rt.cpuPercent))
                HubStat(stringResource(R.string.stat_ram), "${rt.rssMb?.let { formatMb(it) } ?: "–"} / ${formatMb(server.xmxMb)}")
                Column {
                  Text(stringResource(R.string.stat_uptime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  UptimeText(rt.startedAt, style = MaterialTheme.typography.titleSmall)
                }
              }
            }
            if (rt.state == ServerState.CRASHED && rt.lastExitCode != null) {
              Text(stringResource(R.string.hub_exit_code, rt.lastExitCode), style = MaterialTheme.typography.bodySmall)
            }
            if (server.launchSpec == null && rt.state != ServerState.INSTALLING) {
              Text(stringResource(R.string.hub_not_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            ControlButtons(
              state = rt.state,
              canStart = server.launchSpec != null,
              onStart = vm::onStart,
              onStop = vm::onStop,
              onRestart = vm::onRestart,
              onKill = { confirmKill = true },
            )
          }
        }
      }
      item(span = { GridItemSpan(maxLineSpan) }) {
        Card(
          Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                  stringResource(R.string.ops_section_local),
                  style = MaterialTheme.typography.titleMedium,
                )
              }
              OutlinedButton(
                onClick = { navigate(Routes.network(id)) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
              ) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.ops_ingress_configure), style = MaterialTheme.typography.labelMedium)
              }
            }

            // Public Address (Internet / Friends)
            val pub = ui.publicAddress
            if (pub != null) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text(
                    stringResource(R.string.ops_ingress_public),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                  )
                  Text(
                    pub,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                  )
                  Text(
                    stringResource(R.string.ops_ingress_friends_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = {
                  copyToClipboard(context, "public_address", pub)
                  scope.launch { snackbar.showSnackbar(copied) }
                }) {
                  Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
              }
              HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              )
            }

            // Local LAN Address (Wi-Fi)
            val address = ui.lanIp?.let { "$it:${server.port}" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(
                  stringResource(R.string.ops_ingress_lan),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  address ?: stringResource(R.string.hub_no_network),
                  style = MaterialTheme.typography.titleSmall,
                  fontFamily = FontFamily.Monospace,
                )
                Text(
                  stringResource(R.string.ops_ingress_wifi_note),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (address != null) {
                IconButton(onClick = {
                  copyToClipboard(context, "address", address)
                  scope.launch { snackbar.showSnackbar(copied) }
                }) {
                  Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
              }
            }

            // Bedrock Crossplay Badge
            if (ui.isBedrockActive) {
              HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
              )
              Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  Icons.Filled.Extension,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                  tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                  Text(
                    stringResource(R.string.ops_bedrock_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    stringResource(R.string.ops_bedrock_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
      items(tiles, key = { it.route }) { t ->
        Card(Modifier.fillMaxWidth().clickable { navigate(t.route) }) {
          Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(t.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(t.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
          }
        }
      }
    }

    if (pendingStart) {
      ConfirmDialog(
        title = stringResource(R.string.ram_confirm_title),
        text = stringResource(R.string.ram_confirm_text, formatMb(server.xmxMb)),
        onConfirm = vm::confirmStart,
        onDismiss = vm::dismissStart,
        confirmLabel = stringResource(R.string.action_start),
      )
    }
    if (confirmKill) {
      ConfirmDialog(
        title = stringResource(R.string.hub_kill_title),
        text = stringResource(R.string.hub_kill_text),
        onConfirm = { confirmKill = false; vm.onKill() },
        onDismiss = { confirmKill = false },
        confirmLabel = stringResource(R.string.action_kill),
      )
    }
  }
}

@Composable
private fun HubStat(label: String, value: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleSmall)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlButtons(
  state: ServerState,
  canStart: Boolean,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onRestart: () -> Unit,
  onKill: () -> Unit,
) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (state) {
      ServerState.STOPPED, ServerState.CRASHED -> Button(onClick = onStart, enabled = canStart) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.action_start), Modifier.padding(start = 6.dp))
      }
      ServerState.STARTING -> {
        FilledTonalButton(onClick = onStop) {
          Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
          Text(stringResource(R.string.action_stop), Modifier.padding(start = 6.dp))
        }
        KillButton(onKill)
      }
      ServerState.RUNNING -> {
        FilledTonalButton(onClick = onStop) {
          Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
          Text(stringResource(R.string.action_stop), Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = onRestart) {
          Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
          Text(stringResource(R.string.action_restart), Modifier.padding(start = 6.dp))
        }
        KillButton(onKill)
      }
      ServerState.STOPPING -> KillButton(onKill)
      ServerState.INSTALLING -> Unit
    }
  }
}

@Composable
private fun KillButton(onKill: () -> Unit) {
  OutlinedButton(onClick = onKill, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
    Text(stringResource(R.string.action_kill), Modifier.padding(start = 6.dp))
  }
}
