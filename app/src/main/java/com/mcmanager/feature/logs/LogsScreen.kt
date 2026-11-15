package com.mcmanager.feature.logs

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.core.ui.theme.MonoTextStyle
import com.mcmanager.feature.server.formatBytes
import java.text.DateFormat
import java.util.Date

private val WarnColor = Color(0xFFFFA000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(serverId: Long, onBack: () -> Unit) {
  val vm: LogsViewModel = hiltViewModel()
  val ui by vm.ui.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val exportOk = stringResource(R.string.logs_export_ok)
  val exportFail = stringResource(R.string.logs_export_failed)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    if (uri != null) vm.exportTo(uri)
  }
  LaunchedEffect(Unit) {
    vm.exportEvents.collect { snackbar.showSnackbar(if (it == LogsViewModel.ExportResult.OK) exportOk else exportFail) }
  }

  val selected = ui.selected
  BackHandler(enabled = selected != null) { vm.close() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(selected?.name ?: stringResource(R.string.tile_logs)) },
        navigationIcon = { BackButton { if (selected != null) vm.close() else onBack() } },
        actions = {
          if (selected != null) {
            IconButton(onClick = { launcher.launch(selected.name) }) {
              Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.logs_export))
            }
          } else {
            IconButton(onClick = vm::loadFiles) {
              Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (selected == null) FileList(ui, vm) else Viewer(ui, vm, live = selected.name == "latest.log")
    }
  }
}

@Composable
private fun FileList(ui: LogsUi, vm: LogsViewModel) {
  when {
    ui.loadingFiles && ui.files.isEmpty() -> LoadingBox()
    ui.files.isEmpty() -> EmptyState(stringResource(R.string.logs_empty), icon = Icons.Filled.Article)
    else -> {
      val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
      LazyColumn(Modifier.fillMaxSize()) {
        items(ui.files, key = { it.path }) { f ->
          ListItem(
            headlineContent = { Text(f.name) },
            supportingContent = { Text("${formatBytes(f.size)} · ${fmt.format(Date(f.modified))}") },
            leadingContent = { Icon(Icons.Filled.Article, contentDescription = null) },
            modifier = Modifier.clickable { vm.open(f) },
          )
          HorizontalDivider()
        }
      }
    }
  }
}

@Composable
private fun Viewer(ui: LogsUi, vm: LogsViewModel, live: Boolean) {
  val listState = rememberLazyListState()
  var autoScroll by remember { mutableStateOf(true) }
  val q = ui.query.trim()
  val currentLine = ui.matches.getOrNull(ui.matchPos)

  LaunchedEffect(currentLine) {
    if (currentLine != null) listState.scrollToItem(currentLine)
  }
  LaunchedEffect(ui.lines.size, autoScroll, live, q) {
    if (live && autoScroll && q.isEmpty() && ui.lines.isNotEmpty()) listState.scrollToItem(ui.lines.lastIndex)
  }

  Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = ui.query,
      onValueChange = vm::setQuery,
      modifier = Modifier.weight(1f),
      singleLine = true,
      placeholder = { Text(stringResource(R.string.action_search)) },
    )
    if (q.isNotEmpty()) {
      Text(
        if (ui.matches.isEmpty()) "0" else "${ui.matchPos + 1}/${ui.matches.size}",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      IconButton(onClick = vm::prevMatch) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.logs_prev)) }
      IconButton(onClick = vm::nextMatch) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.logs_next)) }
    }
    if (live) {
      IconToggleButton(checked = autoScroll, onCheckedChange = { autoScroll = it }) {
        Icon(
          Icons.Filled.KeyboardDoubleArrowDown,
          contentDescription = stringResource(R.string.console_autoscroll),
          tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
      }
    }
  }
  when {
    ui.readingFile -> LoadingBox()
    ui.error != null -> EmptyState(ui.error.ifBlank { stringResource(R.string.error_generic) })
    else -> {
      val hi = MaterialTheme.colorScheme.tertiaryContainer
      val hiText = MaterialTheme.colorScheme.onTertiaryContainer
      val cur = MaterialTheme.colorScheme.primaryContainer
      val err = MaterialTheme.colorScheme.error
      val onSurface = MaterialTheme.colorScheme.onSurface
      SelectionContainer {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
          contentPadding = PaddingValues(8.dp),
        ) {
          itemsIndexed(ui.lines) { index, line ->
            val color = when {
              line.contains("ERROR") || line.contains("Exception") -> err
              line.contains("WARN") -> WarnColor
              else -> onSurface
            }
            if (q.isNotEmpty() && line.contains(q, ignoreCase = true)) {
              val isCurrent = index == currentLine
              Text(
                buildAnnotatedString {
                  var pos = 0
                  while (true) {
                    val at = line.indexOf(q, pos, ignoreCase = true)
                    if (at < 0) break
                    append(line.substring(pos, at))
                    withStyle(SpanStyle(background = if (isCurrent) cur else hi, color = hiText)) { append(line.substring(at, at + q.length)) }
                    pos = at + q.length
                  }
                  append(line.substring(pos))
                },
                style = MonoTextStyle,
                color = color,
              )
            } else {
              Text(line, style = MonoTextStyle, color = color)
            }
          }
        }
      }
    }
  }
}
