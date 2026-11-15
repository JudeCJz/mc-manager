package com.mcmanager.feature.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YamlConfigScreen(serverId: Long, file: String, onBack: () -> Unit) {
  val vm: YamlConfigViewModel = hiltViewModel()
  LaunchedEffect(serverId, file) { vm.init(serverId, file) }
  val st by vm.state.collectAsStateWithLifecycle()
  val snack = remember { SnackbarHostState() }
  var query by remember { mutableStateOf("") }
  var confirmExit by remember { mutableStateOf(false) }
  val expanded = remember { mutableStateMapOf<String, Boolean>() }

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
  BackHandler(enabled = st.dirty) { confirmExit = true }

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    topBar = {
      TopAppBar(
        title = { Text(file.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { BackButton { if (st.dirty) confirmExit = true else onBack() } },
        actions = {
          IconButton(onClick = { vm.requestSave() }, enabled = st.dirty && !st.saving && !st.hasErrors()) {
            Icon(Icons.Filled.Save, stringResource(R.string.action_save))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        st.loading -> LoadingBox()
        st.tooBig -> EmptyState(stringResource(R.string.files_err_too_big))
        st.missing -> EmptyState(
          stringResource(R.string.yaml_missing_title),
          subtitle = stringResource(R.string.yaml_missing_msg, file),
          icon = Icons.Filled.FolderOff,
        )
        else -> {
          TabRow(selectedTabIndex = if (st.mode == YMode.FORM) 0 else 1) {
            Tab(st.mode == YMode.FORM, { vm.setMode(YMode.FORM) }, text = { Text(stringResource(R.string.yaml_tab_form)) })
            Tab(st.mode == YMode.RAW, { vm.setMode(YMode.RAW) }, text = { Text(stringResource(R.string.yaml_tab_raw)) })
          }
          if (st.mode == YMode.FORM) {
            FormBody(st, query, { query = it }, expanded, vm)
          } else {
            RawBody(st.rawText, vm::editRaw)
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
  if (st.showCommentWarning) {
    ConfirmDialog(
      title = stringResource(R.string.yaml_comment_warn_title),
      text = stringResource(R.string.yaml_comment_warn),
      onConfirm = { vm.confirmCommentWarning() },
      onDismiss = { vm.dismissCommentWarning() },
      confirmLabel = stringResource(R.string.action_save),
    )
  }
  st.rawError?.let { err ->
    AlertDialog(
      onDismissRequest = { vm.dismissRawError() },
      title = { Text(stringResource(R.string.yaml_invalid_title)) },
      text = { Text(err) },
      confirmButton = { TextButton(onClick = { vm.dismissRawError() }) { Text(stringResource(R.string.action_ok)) } },
    )
  }
}

@Composable
private fun RawBody(text: String, onChange: (String) -> Unit) {
  val v = rememberScrollState()
  val h = rememberScrollState()
  BasicTextField(
    value = text,
    onValueChange = onChange,
    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    modifier = Modifier.fillMaxSize().verticalScroll(v).horizontalScroll(h).padding(8.dp)
      .widthIn(min = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp),
  )
}

@Composable
private fun FormBody(
  st: YamlUi,
  query: String,
  onQuery: (String) -> Unit,
  expanded: MutableMap<String, Boolean>,
  vm: YamlConfigViewModel,
) {
  OutlinedTextField(
    query, onQuery, singleLine = true,
    leadingIcon = { Icon(Icons.Filled.Search, null) },
    placeholder = { Text(stringResource(R.string.yaml_filter)) },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  )
  if (st.parseError != null) {
    Text(st.parseError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    return
  }
  val q = query.trim().lowercase()
  val visible = remember(st.nodes, q, expanded.toMap()) {
    if (q.isNotEmpty()) {
      st.nodes.filter { it.kind != YKind.SECTION && it.display.lowercase().contains(q) }
    } else {
      val out = ArrayList<YNode>()
      var hideBelow = Int.MAX_VALUE
      for (n in st.nodes) {
        if (n.depth > hideBelow) continue
        hideBelow = Int.MAX_VALUE
        out.add(n)
        if (n.kind == YKind.SECTION && expanded[n.pathKey] != true) hideBelow = n.depth
      }
      out
    }
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(visible, key = { it.pathKey }) { n ->
      val indent = if (q.isNotEmpty()) 0 else n.depth * 12
      when (n.kind) {
        YKind.SECTION -> {
          val open = expanded[n.pathKey] == true
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(start = (12 + indent).dp, end = 12.dp, top = 4.dp, bottom = 2.dp)
              .clickable { expanded[n.pathKey] = !open },
          ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(n.key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
              Text(n.text, style = MaterialTheme.typography.labelSmall)
              Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
          }
        }
        else -> LeafEditor(n, st, indent, if (q.isNotEmpty()) n.display else n.key, vm)
      }
    }
  }
}

@Composable
private fun LeafEditor(n: YNode, st: YamlUi, indent: Int, label: String, vm: YamlConfigViewModel) {
  val value = st.edits[n.pathKey] ?: n.text
  val changed = st.edits.containsKey(n.pathKey)
  val pad = Modifier.fillMaxWidth().padding(start = (16 + indent).dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
  when (n.kind) {
    YKind.BOOL -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
      Text(
        label + if (changed) " *" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
        color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Switch(value.trim().equals("true", true), { vm.editLeaf(n, it.toString()) })
    }
    YKind.COMPLEX -> Column(pad) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(stringResource(R.string.yaml_complex), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    else -> OutlinedTextField(
      value, { vm.editLeaf(n, it) },
      modifier = pad,
      singleLine = n.kind != YKind.LIST,
      label = { Text(label + if (changed) " *" else "") },
      isError = st.leafError(n, value),
      supportingText = if (n.kind == YKind.LIST) ({ Text(stringResource(R.string.yaml_list_hint)) }) else null,
      keyboardOptions = KeyboardOptions(keyboardType = if (n.kind == YKind.NUM) KeyboardType.Decimal else KeyboardType.Text),
    )
  }
}
