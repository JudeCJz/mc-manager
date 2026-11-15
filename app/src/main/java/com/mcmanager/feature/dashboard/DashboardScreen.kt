package com.mcmanager.feature.dashboard

import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.feature.server.RamBar
import com.mcmanager.feature.server.StateChip
import com.mcmanager.feature.server.UptimeText
import com.mcmanager.feature.server.formatCpu
import com.mcmanager.feature.server.formatMb
import com.mcmanager.feature.server.formatTps
import com.mcmanager.feature.server.rememberBatteryIgnored
import com.mcmanager.feature.server.requestIgnoreBatteryOptimizations

private const val UI_PREFS = "mcmanager_ui"
private const val KEY_BATTERY_DISMISSED = "battery_banner_dismissed"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpenServer: (Long) -> Unit, onCreate: () -> Unit) {
  val vm: DashboardViewModel = hiltViewModel()
  val ui by vm.ui.collectAsStateWithLifecycle()
  val pending by vm.pendingStart.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val generic = stringResource(R.string.error_generic)
  LaunchedEffect(Unit) {
    vm.events.collect { snackbar.showSnackbar(it.ifBlank { generic }) }
  }

  val context = LocalContext.current
  val batteryIgnored by rememberBatteryIgnored()
  var bannerDismissed by remember {
    mutableStateOf(context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_DISMISSED, false))
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_servers)) }) },
    snackbarHost = { SnackbarHost(snackbar) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onCreate,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.dash_create)) },
      )
    },
  ) { padding ->
    if (!ui.loaded) {
      LoadingBox(Modifier.fillMaxSize().padding(padding))
    } else {
      LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (!batteryIgnored && !bannerDismissed) {
          item {
            BatteryBanner(
              onRequest = { requestIgnoreBatteryOptimizations(context) },
              onDismiss = {
                bannerDismissed = true
                context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BATTERY_DISMISSED, true).apply()
              },
            )
          }
        }
        item { PhoneRamCard(ui.usedXmxMb, ui.totalRamMb) }
        if (ui.cards.isEmpty()) {
          item {
            EmptyState(
              title = stringResource(R.string.dash_empty_title),
              subtitle = stringResource(R.string.dash_empty_subtitle),
              modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
              icon = Icons.Filled.Dns,
            )
          }
        }
        items(ui.cards, key = { it.server.id }) { card ->
          ServerCard(
            card = card,
            onOpen = { onOpenServer(card.server.id) },
            onStart = { vm.onStart(card.server) },
            onStop = { vm.onStop(card.server.id) },
          )
        }
      }
    }
  }

  pending?.let { s ->
    ConfirmDialog(
      title = stringResource(R.string.ram_confirm_title),
      text = stringResource(R.string.ram_confirm_text, formatMb(s.xmxMb)),
      onConfirm = vm::confirmStart,
      onDismiss = vm::dismissStart,
      confirmLabel = stringResource(R.string.action_start),
    )
  }
}

@Composable
private fun BatteryBanner(onRequest: () -> Unit, onDismiss: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.BatteryAlert, contentDescription = null)
        Text(stringResource(R.string.battery_banner_title), style = MaterialTheme.typography.titleSmall)
      }
      Text(stringResource(R.string.battery_banner_text), style = MaterialTheme.typography.bodySmall)
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        Button(onClick = onRequest) { Text(stringResource(R.string.battery_allow)) }
      }
    }
  }
}

@Composable
private fun PhoneRamCard(usedMb: Int, totalMb: Int) {
  Card {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.dash_phone_ram), style = MaterialTheme.typography.titleSmall)
        Text(
          "${formatMb(usedMb)} / ${formatMb(totalMb)}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      RamBar(usedMb, totalMb)
      Text(
        stringResource(R.string.dash_ram_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(card: ServerCardUi, onOpen: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
  val s = card.server
  val rt = card.runtime
  val live = rt.state == ServerState.RUNNING || rt.state == ServerState.STARTING
  Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            "${s.engine.label} ${s.mcVersion} · :${s.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StateChip(rt.state)
      }
      if (card.update != null) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
          Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.dash_update_available, card.update), style = MaterialTheme.typography.labelMedium)
          }
        }
      }
      if (live) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Stat(stringResource(R.string.stat_players), "${rt.onlinePlayers.size}/${rt.maxPlayers ?: s.maxPlayersFallback()}")
          Stat(stringResource(R.string.stat_tps), formatTps(rt.tps))
          Stat(stringResource(R.string.stat_ram), "${rt.rssMb?.let { formatMb(it) } ?: "–"} / ${formatMb(s.xmxMb)}")
          Stat(stringResource(R.string.stat_cpu), formatCpu(rt.cpuPercent))
          Column {
            Text(stringResource(R.string.stat_uptime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UptimeText(rt.startedAt)
          }
        }
      } else if (rt.state == ServerState.INSTALLING) {
        Text(rt.message.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } else if (rt.state == ServerState.CRASHED && !rt.message.isNullOrBlank()) {
        Text(rt.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
      } else {
        Text(
          stringResource(R.string.stat_ram_alloc, formatMb(s.xmxMb)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        when (rt.state) {
          ServerState.STOPPED, ServerState.CRASHED -> FilledTonalButton(onClick = onStart, enabled = s.launchSpec != null) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.action_start))
          }
          ServerState.STARTING, ServerState.RUNNING -> FilledTonalButton(onClick = onStop) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.action_stop))
          }
          ServerState.STOPPING, ServerState.INSTALLING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        }
      }
    }
  }
}

private fun com.mcmanager.core.data.db.ServerEntity.maxPlayersFallback(): String = "?"

@Composable
private fun Stat(label: String, value: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}
