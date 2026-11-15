package com.mcmanager.feature.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox

private sealed interface PDialog {
  data class Kick(val name: String) : PDialog
  data class BanOnline(val name: String) : PDialog
  data object AddWhitelist : PDialog
  data object AddOp : PDialog
  data object AddBan : PDialog
  data object AddIpBan : PDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(serverId: Long, onBack: () -> Unit) {
  val vm: PlayersViewModel = hiltViewModel()
  LaunchedEffect(serverId) { vm.init(serverId) }
  val st by vm.state.collectAsStateWithLifecycle()
  val snack = remember { SnackbarHostState() }
  var tab by remember { mutableIntStateOf(0) }
  var dialog by remember { mutableStateOf<PDialog?>(null) }
  LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

  val tabs = listOf(
    R.string.players_tab_online, R.string.players_tab_whitelist, R.string.players_tab_ops,
    R.string.players_tab_bans, R.string.players_tab_ipbans,
  )

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.players_title)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = { vm.refreshOnline(); vm.loadAll() }) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
        tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(stringResource(t)) }) }
      }
      if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      if (st.loading) {
        LoadingBox()
      } else if (st.serverMissing) {
        EmptyState(stringResource(R.string.files_server_missing))
      } else {
        if (tab != 0 && !st.running) {
          Text(
            stringResource(R.string.players_edit_files_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
          )
        }
        when (tab) {
          0 -> OnlineTab(st, vm, onKick = { dialog = PDialog.Kick(it) }, onBan = { dialog = PDialog.BanOnline(it) })
          1 -> WhitelistTab(st, vm) { dialog = PDialog.AddWhitelist }
          2 -> OpsTab(st, vm) { dialog = PDialog.AddOp }
          3 -> BansTab(st, vm) { dialog = PDialog.AddBan }
          else -> IpBansTab(st, vm) { dialog = PDialog.AddIpBan }
        }
      }
    }
  }

  when (val d = dialog) {
    is PDialog.Kick -> ReasonDialog(R.string.players_kick, d.name, false, "", { dialog = null }) { _, r -> vm.kick(d.name, r); dialog = null }
    is PDialog.BanOnline -> ReasonDialog(R.string.players_ban, d.name, false, "", { dialog = null }) { _, r -> vm.ban(d.name, r); dialog = null }
    PDialog.AddWhitelist -> ReasonDialog(R.string.players_add_whitelist, "", true, null, { dialog = null }) { n, _ -> vm.addWhitelist(n); dialog = null }
    PDialog.AddBan -> ReasonDialog(R.string.players_add_ban, "", true, "", { dialog = null }) { n, r -> vm.ban(n, r); dialog = null }
    PDialog.AddIpBan -> ReasonDialog(R.string.players_add_ipban, "", true, "", { dialog = null }, nameLabel = R.string.players_ip) { n, r -> vm.banIp(n, r); dialog = null }
    PDialog.AddOp -> OpDialog(st.running, { dialog = null }) { n, l -> vm.addOp(n, l); dialog = null }
    null -> Unit
  }
}

@Composable
private fun AddBar(label: Int, onAdd: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
    FilledTonalButton(onClick = onAdd) {
      Icon(Icons.Filled.Add, null)
      Text(stringResource(label), Modifier.padding(start = 8.dp))
    }
  }
}

@Composable
private fun OnlineTab(st: PlayersUi, vm: PlayersViewModel, onKick: (String) -> Unit, onBan: (String) -> Unit) {
  if (!st.running) {
    EmptyState(stringResource(R.string.players_not_running), icon = Icons.Filled.Person)
    return
  }
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.players_online_count, st.online.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = { vm.refreshOnline() }) { Text(stringResource(R.string.players_send_list)) }
  }
  if (st.online.isEmpty()) {
    EmptyState(stringResource(R.string.players_none_online), icon = Icons.Filled.Person)
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(st.online, key = { it }) { name ->
      var menu by remember { mutableStateOf(false) }
      ListItem(
        leadingContent = { Icon(Icons.Filled.Person, null) },
        headlineContent = { Text(name) },
        trailingContent = {
          Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
              DropdownMenuItem(text = { Text(stringResource(R.string.players_kick)) }, onClick = { menu = false; onKick(name) })
              DropdownMenuItem(text = { Text(stringResource(R.string.players_ban)) }, onClick = { menu = false; onBan(name) })
              DropdownMenuItem(text = { Text(stringResource(R.string.players_op)) }, onClick = { menu = false; vm.opOnline(name, true) })
              DropdownMenuItem(text = { Text(stringResource(R.string.players_deop)) }, onClick = { menu = false; vm.opOnline(name, false) })
              DropdownMenuItem(text = { Text(stringResource(R.string.players_whitelist_add)) }, onClick = { menu = false; vm.whitelistOnline(name) })
            }
          }
        },
      )
    }
  }
}

@Composable
private fun WhitelistTab(st: PlayersUi, vm: PlayersViewModel, onAdd: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.players_whitelist_enabled), Modifier.weight(1f))
    Switch(st.whitelistOn, { vm.setWhitelistEnabled(it) })
  }
  AddBar(R.string.action_add, onAdd)
  if (st.whitelist.isEmpty()) {
    EmptyState(stringResource(R.string.players_whitelist_empty))
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(st.whitelist, key = { it.name.lowercase() }) { e ->
      ListItem(
        headlineContent = { Text(e.name) },
        supportingContent = { Text(e.uuid, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { IconButton(onClick = { vm.removeWhitelist(e) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) } },
      )
    }
  }
}

@Composable
private fun OpsTab(st: PlayersUi, vm: PlayersViewModel, onAdd: () -> Unit) {
  AddBar(R.string.action_add, onAdd)
  if (st.running) {
    Text(
      stringResource(R.string.players_op_level_stopped),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
  }
  if (st.ops.isEmpty()) {
    EmptyState(stringResource(R.string.players_ops_empty))
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(st.ops, key = { it.name.lowercase() }) { e ->
      var menu by remember { mutableStateOf(false) }
      ListItem(
        headlineContent = { Text(e.name) },
        supportingContent = { Text(e.uuid, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
              AssistChip(
                onClick = { if (!st.running) menu = true },
                label = { Text(stringResource(R.string.players_level, e.level)) },
                enabled = !st.running,
              )
              DropdownMenu(menu, { menu = false }) {
                (1..4).forEach { l ->
                  DropdownMenuItem(text = { Text(stringResource(R.string.players_level, l)) }, onClick = { menu = false; vm.setOpLevel(e, l) })
                }
              }
            }
            IconButton(onClick = { vm.removeOp(e) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
          }
        },
      )
    }
  }
}

@Composable
private fun BansTab(st: PlayersUi, vm: PlayersViewModel, onAdd: () -> Unit) {
  AddBar(R.string.action_add, onAdd)
  if (st.bans.isEmpty()) {
    EmptyState(stringResource(R.string.players_bans_empty))
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(st.bans, key = { it.name.lowercase() }) { e ->
      ListItem(
        headlineContent = { Text(e.name) },
        supportingContent = { Text(e.reason, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { TextButton(onClick = { vm.pardon(e) }) { Text(stringResource(R.string.players_pardon)) } },
      )
    }
  }
}

@Composable
private fun IpBansTab(st: PlayersUi, vm: PlayersViewModel, onAdd: () -> Unit) {
  AddBar(R.string.action_add, onAdd)
  if (st.ipBans.isEmpty()) {
    EmptyState(stringResource(R.string.players_ipbans_empty))
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(st.ipBans, key = { it.ip }) { e ->
      ListItem(
        headlineContent = { Text(e.ip) },
        supportingContent = { Text(e.reason, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { TextButton(onClick = { vm.pardonIp(e) }) { Text(stringResource(R.string.players_pardon)) } },
      )
    }
  }
}

/** [reason] null = no reason field. [askName] shows an editable name/UUID/IP field. */
@Composable
private fun ReasonDialog(
  title: Int,
  fixedName: String,
  askName: Boolean,
  reason: String?,
  onDismiss: () -> Unit,
  nameLabel: Int = R.string.players_name_or_uuid,
  onOk: (String, String) -> Unit,
) {
  var name by remember { mutableStateOf(fixedName) }
  var why by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(title) + if (!askName) " $fixedName" else "") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (askName) OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(nameLabel)) })
        if (reason != null) OutlinedTextField(why, { why = it }, singleLine = true, label = { Text(stringResource(R.string.players_reason)) })
      }
    },
    confirmButton = { TextButton(onClick = { onOk(name.trim(), why) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_ok)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
private fun OpDialog(running: Boolean, onDismiss: () -> Unit, onOk: (String, Int) -> Unit) {
  var name by remember { mutableStateOf("") }
  var level by remember { mutableIntStateOf(4) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.players_add_op)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.players_name_or_uuid)) })
        if (!running) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { l -> FilterChip(level == l, { level = l }, label = { Text(l.toString()) }) }
          }
        } else {
          Text(stringResource(R.string.players_op_level_stopped), style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = { TextButton(onClick = { onOk(name.trim(), level) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_ok)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}
