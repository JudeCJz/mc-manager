package com.mcmanager.feature.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.core.ui.components.SectionHeader

private sealed interface PRow {
  data class Header(val group: PropGroup) : PRow
  data class Field(val def: PropDef) : PRow
}

private fun groupLabel(g: PropGroup) = when (g) {
  PropGroup.GAMEPLAY -> R.string.props_group_gameplay
  PropGroup.WORLD -> R.string.props_group_world
  PropGroup.PLAYERS -> R.string.props_group_players
  PropGroup.NETWORK -> R.string.props_group_network
  PropGroup.ADVANCED -> R.string.props_group_advanced
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesScreen(serverId: Long, onBack: () -> Unit) {
  val vm: PropertiesViewModel = hiltViewModel()
  LaunchedEffect(serverId) { vm.init(serverId) }
  val st by vm.state.collectAsStateWithLifecycle()
  val snack = remember { SnackbarHostState() }
  var query by remember { mutableStateOf("") }
  var confirmExit by remember { mutableStateOf(false) }
  val dirty = st.edits.isNotEmpty()

  val savedMsg = stringResource(R.string.props_saved)
  val restartMsg = stringResource(R.string.props_restart_needed)
  val restartLabel = stringResource(R.string.action_restart)
  LaunchedEffect(Unit) {
    vm.events.collect { ev ->
      when (ev) {
        is PropsEvent.Saved ->
          if (ev.restartNeeded) {
            val r = snack.showSnackbar(restartMsg, restartLabel, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.restart()
          } else {
            snack.showSnackbar(savedMsg)
          }
        is PropsEvent.Failed -> snack.showSnackbar(ev.message)
      }
    }
  }
  BackHandler(enabled = dirty) { confirmExit = true }

  val rows = remember(st.base.keys, st.extraKeys, query) {
    val q = query.trim().lowercase()
    val all = PropertiesSchema.defs + st.extraKeys.map { PropDef(it, PropType.STRING, "", PropGroup.ADVANCED, "") }
    val filtered = all.filter { q.isEmpty() || it.key.lowercase().contains(q) || it.desc.lowercase().contains(q) }
    buildList<PRow> {
      for (g in PropGroup.entries) {
        val inGroup = filtered.filter { it.group == g }
        if (inGroup.isEmpty()) continue
        add(PRow.Header(g))
        inGroup.forEach { add(PRow.Field(it)) }
      }
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    topBar = {
      TopAppBar(
        title = { Text("server.properties") },
        navigationIcon = { BackButton { if (dirty) confirmExit = true else onBack() } },
        actions = {
          IconButton(onClick = { vm.save() }, enabled = dirty && !st.hasErrors() && !st.saving) {
            Icon(Icons.Filled.Save, stringResource(R.string.action_save))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        st.loading -> LoadingBox()
        st.serverMissing -> EmptyState(stringResource(R.string.files_server_missing))
        !st.fileExists -> EmptyState(
          title = stringResource(R.string.props_missing_title),
          subtitle = stringResource(R.string.props_missing_msg),
          icon = Icons.Filled.Description,
          action = {
            FilledTonalButton(onClick = { vm.createDefaults() }) { Text(stringResource(R.string.props_create_defaults)) }
          },
        )
        else -> {
          OutlinedTextField(
            query, { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.action_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
          LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = {
              when (it) {
                is PRow.Header -> "h:" + it.group.name
                is PRow.Field -> "f:" + it.def.key
              }
            }) { row ->
              when (row) {
                is PRow.Header -> SectionHeader(stringResource(groupLabel(row.group)))
                is PRow.Field -> PropField(
                  row.def,
                  value = st.value(row.def),
                  changed = st.edits.containsKey(row.def.key),
                  enabled = row.def.key != "server-port" || st.portEditable,
                  onChange = { vm.edit(row.def.key, it) },
                )
              }
            }
          }
        }
      }
    }
  }

  if (confirmExit) {
    ConfirmDialog(
      title = stringResource(R.string.editor_unsaved_title),
      text = stringResource(R.string.editor_unsaved_msg),
      onConfirm = { confirmExit = false; onBack() },
      onDismiss = { confirmExit = false },
      confirmLabel = stringResource(R.string.editor_discard),
    )
  }
}

@Composable
private fun PropField(def: PropDef, value: String, changed: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
  val error = PropertiesSchema.validate(def, value)
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(def.key, style = MaterialTheme.typography.titleSmall)
          if (changed) {
            Text(
              "  " + stringResource(R.string.props_changed),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        if (def.desc.isNotEmpty()) {
          Text(def.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!enabled) {
          Text(stringResource(R.string.props_port_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
      }
      if (def.type == PropType.BOOL) {
        Switch(checked = value.trim().equals("true", true), onCheckedChange = { onChange(it.toString()) }, enabled = enabled)
      }
    }
    when (def.type) {
      PropType.BOOL -> Unit
      PropType.INT -> OutlinedTextField(
        value, onChange, singleLine = true, enabled = enabled, isError = error != null,
        supportingText = {
          Text(
            error ?: buildString {
              append(stringResource(R.string.props_default, def.default))
              if (def.min != null || def.max != null) append("  [${def.min ?: ""}..${def.max ?: ""}]")
            },
          )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      )
      PropType.STRING -> OutlinedTextField(
        value, onChange, singleLine = true, enabled = enabled,
        visualTransformation = if (def.secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      )
      PropType.ENUM -> {
        var open by remember { mutableStateOf(false) }
        val options = if (value in def.options) def.options else def.options + value
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 4.dp)) {
          OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
          }
          DropdownMenu(open, { open = false }) {
            options.forEach { o ->
              DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onChange(o) })
            }
          }
        }
      }
    }
  }
}
