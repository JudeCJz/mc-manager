package com.mcmanager.feature.console

import android.view.View
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.ConsoleLine
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.theme.MonoTextStyle
import com.mcmanager.feature.server.copyToClipboard
import kotlinx.coroutines.launch

private val QuickCommands = listOf("list", "tps", "save-all", "weather clear", "time set day")
private val WarnColor = Color(0xFFFFA000)

@Composable
private fun lineColor(line: ConsoleLine): Color {
  val scheme = MaterialTheme.colorScheme
  return when (line.kind) {
    ConsoleLine.Kind.ERR -> scheme.error
    ConsoleLine.Kind.CMD -> scheme.primary
    ConsoleLine.Kind.SYSTEM -> scheme.tertiary
    ConsoleLine.Kind.OUT -> {
      val t = line.text
      when {
        t.contains("ERROR") || t.contains("Exception") || t.contains("FATAL") -> scheme.error
        t.contains("WARN") -> WarnColor
        else -> scheme.onSurface
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(serverId: Long, onBack: () -> Unit) {
  val vm: ConsoleViewModel = hiltViewModel()
  val lines by vm.lines.collectAsStateWithLifecycle()
  val settings by vm.settings.collectAsStateWithLifecycle()
  val input by vm.input.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val copiedMsg = stringResource(R.string.console_copied)
  val listState = rememberLazyListState()
  var autoScroll by remember { mutableStateOf(true) }

  // Keep the screen on while the console is visible (if enabled).
  val view = LocalView.current
  DisposableEffect(settings.keepScreenAwakeOnConsole) {
    val prev = view.keepScreenOn
    if (settings.keepScreenAwakeOnConsole) view.keepScreenOn = true
    onDispose { view.keepScreenOn = prev }
  }

  // User dragging the list towards older lines pauses auto-scroll; reaching the bottom resumes it.
  val pauseConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) autoScroll = false
        return Offset.Zero
      }
    }
  }
  LaunchedEffect(listState) {
    snapshotFlow { !listState.canScrollForward }.collect { atBottom -> if (atBottom) autoScroll = true }
  }
  LaunchedEffect(lines.size, autoScroll) {
    if (autoScroll && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.tile_console)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconToggleButton(checked = autoScroll, onCheckedChange = { autoScroll = it }) {
            Icon(
              Icons.Filled.KeyboardDoubleArrowDown,
              contentDescription = stringResource(R.string.console_autoscroll),
              tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
          }
          IconButton(onClick = {
            copyToClipboard(context, "console", lines.joinToString("\n") { it.text })
            scope.launch { snackbar.showSnackbar(copiedMsg) }
          }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.console_copy_all)) }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
    modifier = Modifier.imePadding(),
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
          SelectionContainer {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize().nestedScroll(pauseConnection),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            ) {
              items(lines, key = { it.seq }) { line ->
                Text(line.text, style = MonoTextStyle, color = lineColor(line))
              }
            }
          }
        }
        if (!autoScroll) {
          SmallFloatingActionButton(
            onClick = {
              autoScroll = true
              scope.launch { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
          ) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.console_jump_bottom)) }
        }
      }
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        QuickCommands.forEach { c ->
          AssistChip(onClick = { vm.sendCommand(c) }, label = { Text(c) })
        }
      }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = vm::historyUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.console_history_up)) }
        IconButton(onClick = vm::historyDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.console_history_down)) }
        OutlinedTextField(
          value = input,
          onValueChange = vm::setInput,
          modifier = Modifier.weight(1f),
          singleLine = true,
          placeholder = { Text(stringResource(R.string.console_hint)) },
          textStyle = MonoTextStyle.copy(fontSize = 14.sp),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
          keyboardActions = KeyboardActions(onSend = { vm.send() }),
        )
        IconButton(onClick = vm::send, enabled = input.isNotBlank()) {
          Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.console_send))
        }
      }
    }
  }
}
