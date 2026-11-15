package com.mcmanager.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(serverId: Long, path: String, onBack: () -> Unit) {
  val vm: TextEditorViewModel = hiltViewModel()
  LaunchedEffect(serverId, path) { vm.init(serverId, path) }
  val st by vm.state.collectAsStateWithLifecycle()
  val snack = remember { SnackbarHostState() }
  var tfv by remember(st.loading) { mutableStateOf(TextFieldValue(st.original)) }
  var showFind by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var findInfo by remember { mutableStateOf<String?>(null) }
  var confirmExit by remember { mutableStateOf(false) }
  val dirty = !st.loading && st.error == EditorError.NONE && tfv.text != st.original
  val vScroll = rememberScrollState()
  val hScroll = rememberScrollState()
  val lineHeightSp = 18f
  val lineHeightPx = with(LocalDensity.current) { lineHeightSp.sp.toPx() }

  val savedMsg = stringResource(R.string.editor_saved)
  LaunchedEffect(st.savedTick) { if (st.savedTick > 0) snack.showSnackbar(savedMsg) }
  LaunchedEffect(st.saveError) {
    st.saveError?.let { snack.showSnackbar(it); vm.clearSaveError() }
  }

  val tryBack = { if (dirty) confirmExit = true else onBack() }
  BackHandler(enabled = dirty) { confirmExit = true }

  val lineCount by remember { derivedStateOf { tfv.text.count { it == '\n' } + 1 } }
  val noMatch = stringResource(R.string.editor_no_match)

  fun findNext() {
    if (query.isEmpty()) return
    val text = tfv.text
    val from = tfv.selection.max.coerceAtMost(text.length)
    var idx = text.indexOf(query, from, ignoreCase = true)
    if (idx < 0) idx = text.indexOf(query, 0, ignoreCase = true)
    if (idx < 0) {
      findInfo = noMatch
    } else {
      tfv = tfv.copy(selection = TextRange(idx, idx + query.length))
      val line = text.substring(0, idx).count { it == '\n' }
      findInfo = "${line + 1}"
    }
  }

  // scroll to the selected match
  LaunchedEffect(tfv.selection, showFind) {
    if (showFind && !tfv.selection.collapsed) {
      val line = tfv.text.substring(0, tfv.selection.min.coerceAtMost(tfv.text.length)).count { it == '\n' }
      vScroll.animateScrollTo((line * lineHeightPx - lineHeightPx * 3).toInt().coerceAtLeast(0))
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (path.contains('/')) {
              Text(
                path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        navigationIcon = { BackButton(tryBack) },
        actions = {
          IconButton(onClick = { showFind = !showFind }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
          IconButton(onClick = { vm.save(tfv.text, force = false) }, enabled = dirty && !st.saving) {
            Icon(Icons.Filled.Save, stringResource(R.string.action_save))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        st.loading -> LoadingBox()
        st.error != EditorError.NONE -> EmptyState(
          stringResource(
            when (st.error) {
              EditorError.TOO_BIG -> R.string.files_err_too_big
              EditorError.OUTSIDE -> R.string.files_err_outside
              EditorError.NOT_FOUND -> R.string.editor_not_found
              else -> R.string.editor_read_failed
            },
          ),
        )
        else -> {
          if (showFind) {
            Row(
              Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              OutlinedTextField(
                query, { query = it; findInfo = null }, singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.action_search)) },
                supportingText = findInfo?.let { { Text(if (it == noMatch) it else stringResource(R.string.editor_line, it)) } },
              )
              IconButton(onClick = { findNext() }) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.editor_next)) }
              IconButton(onClick = { showFind = false }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
            }
          }
          BasicTextField(
            value = tfv,
            onValueChange = { tfv = it },
            textStyle = TextStyle(
              fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = lineHeightSp.sp,
              color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .verticalScroll(vScroll)
              .horizontalScroll(hScroll)
              .padding(horizontal = 8.dp, vertical = 4.dp)
              .widthIn(min = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp),
          )
          Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(stringResource(R.string.editor_lines, lineCount), style = MaterialTheme.typography.labelSmall)
            if (dirty) Text(stringResource(R.string.editor_unsaved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (confirmExit) {
    AlertDialog(
      onDismissRequest = { confirmExit = false },
      title = { Text(stringResource(R.string.editor_unsaved_title)) },
      text = { Text(stringResource(R.string.editor_unsaved_msg)) },
      confirmButton = {
        TextButton(onClick = { confirmExit = false; onBack() }) { Text(stringResource(R.string.editor_discard)) }
      },
      dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
  st.validationError?.let { err ->
    AlertDialog(
      onDismissRequest = { vm.dismissValidation() },
      title = { Text(stringResource(R.string.editor_invalid_title)) },
      text = { Text(stringResource(R.string.editor_invalid_msg, err)) },
      confirmButton = {
        TextButton(onClick = { vm.save(tfv.text, force = true) }) { Text(stringResource(R.string.editor_save_anyway)) }
      },
      dismissButton = { TextButton(onClick = { vm.dismissValidation() }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}
