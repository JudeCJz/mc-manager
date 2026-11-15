package com.mcmanager.feature.plugins

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(serverId: Long, onBack: () -> Unit) {
  val vm: PluginsViewModel = hiltViewModel()
  val st by vm.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  var tab by rememberSaveable { mutableIntStateOf(0) }

  LaunchedEffect(vm) {
    vm.messages.collect { m ->
      val text = m.raw ?: context.getString(m.res, *m.args.toTypedArray())
      snackbar.showSnackbar(text)
    }
  }

  val server = st.server
  val folder = server?.let { Compat.folderFor(it.engine) }
  val modLoader = server?.engine?.isModLoader == true

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(stringResource(if (modLoader) R.string.plugins_title_mods else R.string.plugins_title))
            if (server != null) {
              Text(
                "${server.engine.label} ${server.mcVersion}" + (folder?.let { "  /$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = { BackButton(onBack) },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when {
        server == null -> LoadingBox()
        folder == null -> EmptyState(
          title = stringResource(R.string.plugins_vanilla_title),
          subtitle = stringResource(R.string.plugins_vanilla_text),
          icon = Icons.Filled.Extension,
        )
        else -> {
          TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.plugins_tab_installed)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.plugins_tab_browse)) })
          }
          if (tab == 0) InstalledTab(vm, st, folder) else BrowseTab(vm, st)
        }
      }
    }
  }

  // Detail sheet
  st.detail?.let { d -> DetailSheet(vm, st, d) }

  // Confirm what will be installed
  st.plan?.let { plan ->
    AlertDialog(
      onDismissRequest = vm::cancelPlan,
      title = { Text(stringResource(R.string.plugins_confirm_install_title)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(stringResource(R.string.plugins_will_install), style = MaterialTheme.typography.bodyMedium)
          plan.all.forEachIndexed { i, o ->
            Text(
              "• ${o.projectTitle} ${o.number}" + if (i > 0) "  (${stringResource(R.string.plugins_dependency)})" else "",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          if (plan.unresolved.isNotEmpty()) {
            Text(
              stringResource(R.string.plugins_unresolved, plan.unresolved.joinToString()),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
          if (plan.main.hangarRequires.isNotEmpty()) {
            Text(
              stringResource(R.string.plugins_hangar_requires, plan.main.hangarRequires.joinToString()),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      },
      confirmButton = { TextButton(onClick = vm::confirmInstall) { Text(stringResource(R.string.action_install)) } },
      dismissButton = { TextButton(onClick = vm::cancelPlan) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  // Progress
  st.busyTitle?.let { title ->
    AlertDialog(
      onDismissRequest = {},
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val step = st.busyStep
          if (step != null) {
            Text(
              stringResource(R.string.plugins_step, step.index + 1, step.total, step.name),
              style = MaterialTheme.typography.bodyMedium,
            )
            if (step.fraction != null) LinearProgressIndicator(progress = { step.fraction }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
      },
      confirmButton = {},
    )
  }
}

// ======================================================================================
// Installed tab
// ======================================================================================

@Composable
private fun InstalledTab(vm: PluginsViewModel, st: PluginsUiState, folder: String) {
  var deleteTarget by remember { mutableStateOf<InstalledItem?>(null) }
  val updateCount = st.updates.size

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(onClick = vm::checkUpdates, enabled = !st.checkingUpdates && st.installed.isNotEmpty()) {
        if (st.checkingUpdates) {
          CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Filled.SystemUpdateAlt, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.plugins_check_updates))
      }
      if (updateCount > 0) {
        Button(onClick = vm::updateAllPending) { Text(stringResource(R.string.plugins_update_all, updateCount)) }
      }
      Spacer(Modifier.weight(1f))
      IconButton(onClick = vm::refreshInstalled) {
        Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh))
      }
    }

    when {
      st.installedLoading && st.installed.isEmpty() -> LoadingBox()
      st.installed.isEmpty() -> EmptyState(
        title = stringResource(R.string.plugins_empty, folder),
        subtitle = stringResource(R.string.plugins_empty_hint),
        icon = Icons.Filled.Extension,
      )
      else -> LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
      ) {
        items(st.installed, key = { it.fileName }) { item ->
          InstalledCard(
            item = item,
            update = st.updates[item.baseName],
            onToggle = { vm.setEnabled(item, it) },
            onDelete = { deleteTarget = item },
            onUpdate = { vm.updateItem(item) },
          )
        }
      }
    }
  }

  deleteTarget?.let { item ->
    ConfirmDialog(
      title = stringResource(R.string.plugins_delete_title),
      text = stringResource(R.string.plugins_delete_text, item.fileName),
      confirmLabel = stringResource(R.string.action_delete),
      onConfirm = { vm.delete(item); deleteTarget = null },
      onDismiss = { deleteTarget = null },
    )
  }
}

@Composable
private fun InstalledCard(
  item: InstalledItem,
  update: MrVersion?,
  onToggle: (Boolean) -> Unit,
  onDelete: () -> Unit,
  onUpdate: () -> Unit,
) {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(item.displayName, null, null)
        Column(Modifier.weight(1f)) {
          Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            listOfNotNull(item.displayVersion, formatSize(item.sizeBytes)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            item.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle)
      }
      item.issues.forEach { issue ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
          Text(issueText(issue), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (update != null) {
          FilledTonalButton(onClick = onUpdate) {
            Text(stringResource(R.string.plugins_update_to, update.versionNumber))
          }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDelete) {
          Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
        }
      }
    }
  }
}

@Composable
private fun issueText(issue: PluginIssue): String = when (issue) {
  is PluginIssue.WrongLoader -> stringResource(
    R.string.plugins_issue_wrong_loader,
    issue.found.joinToString { kindName(it) },
    issue.expectedFolder,
  )
  is PluginIssue.McMismatch -> stringResource(R.string.plugins_issue_mc, issue.range, issue.mcVersion)
  is PluginIssue.ApiTooNew -> stringResource(R.string.plugins_issue_api, issue.api, issue.mcVersion)
  is PluginIssue.MissingDeps -> stringResource(R.string.plugins_issue_deps, issue.names.joinToString())
}

private fun kindName(k: JarKind): String = when (k) {
  JarKind.BUKKIT -> "Bukkit/Spigot"
  JarKind.PAPER_PLUGIN -> "Paper"
  JarKind.FABRIC -> "Fabric"
  JarKind.FORGE -> "Forge"
  JarKind.NEOFORGE -> "NeoForge"
}

// ======================================================================================
// Browse tab
// ======================================================================================

@Composable
private fun BrowseTab(vm: PluginsViewModel, st: PluginsUiState) {
  val listState = rememberLazyListState()

  LaunchedEffect(listState, st.browseItems.size) {
    snapshotFlow {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last >= listState.layoutInfo.totalItemsCount - 4
    }.collect { if (it) vm.loadMore() }
  }

  Column(Modifier.fillMaxSize()) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = st.source == Source.MODRINTH,
          onClick = { vm.setSource(Source.MODRINTH) },
          label = { Text("Modrinth") },
        )
        if (st.hangarAvailable) {
          FilterChip(
            selected = st.source == Source.HANGAR,
            onClick = { vm.setSource(Source.HANGAR) },
            label = { Text("Hangar") },
          )
        }
      }
      OutlinedTextField(
        value = st.query,
        onValueChange = vm::setQuery,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        placeholder = { Text(stringResource(R.string.plugins_search_hint)) },
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.plugins_sort), style = MaterialTheme.typography.labelMedium)
        SortMode.entries.forEach { m ->
          FilterChip(
            selected = st.sort == m,
            onClick = { vm.setSort(m) },
            label = {
              Text(
                stringResource(
                  when (m) {
                    SortMode.RELEVANCE -> R.string.plugins_sort_relevance
                    SortMode.DOWNLOADS -> R.string.plugins_sort_downloads
                    SortMode.UPDATED -> R.string.plugins_sort_updated
                  },
                ),
              )
            },
          )
        }
      }
    }

    when {
      st.browseItems.isEmpty() && st.browseLoading -> LoadingBox()
      st.browseItems.isEmpty() && st.browseError != null -> EmptyState(
        title = stringResource(R.string.plugins_browse_failed),
        subtitle = st.browseError,
        action = { OutlinedButton(onClick = vm::loadMore) { Text(stringResource(R.string.action_retry)) } },
      )
      st.browseItems.isEmpty() -> EmptyState(title = stringResource(R.string.plugins_no_results))
      else -> LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
      ) {
        items(st.browseItems, key = { it.source.name + it.id }) { item ->
          BrowseCard(item, vm.icons, onClick = { vm.openDetail(item) })
        }
        if (st.browseLoading) {
          item { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (st.browseError != null) {
          item {
            TextButton(onClick = vm::loadMore, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_retry)) }
          }
        }
      }
    }
  }
}

@Composable
private fun BrowseCard(item: BrowseItem, icons: IconLoader, onClick: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
      Avatar(item.title, item.iconUrl, icons)
      Column(Modifier.weight(1f)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          stringResource(R.string.plugins_by_downloads, item.author, formatCount(item.downloads)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
        )
        Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

// ======================================================================================
// Detail sheet
// ======================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(vm: PluginsViewModel, st: PluginsUiState, d: DetailState) {
  val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val uri = LocalUriHandler.current
  val best = remember(d.versions) { vm.bestOf(d.versions) }

  ModalBottomSheet(onDismissRequest = vm::closeDetail, sheetState = sheet) {
    LazyColumn(
      Modifier.fillMaxWidth(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Avatar(d.item.title, d.item.iconUrl, vm.icons, 56.dp)
          Column {
            Text(d.item.title, style = MaterialTheme.typography.titleLarge)
            Text(
              stringResource(R.string.plugins_by_downloads, d.item.author, formatCount(d.item.downloads)),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      item {
        val body = d.body?.ifBlank { null } ?: d.item.description
        Text(body, style = MaterialTheme.typography.bodyMedium)
      }
      item {
        Text(
          stringResource(R.string.plugins_compatible_versions, st.server?.mcVersion ?: ""),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      when {
        d.loading -> item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        d.error != null -> item { Text(stringResource(R.string.plugins_error, d.error), color = MaterialTheme.colorScheme.error) }
        d.versions.isEmpty() -> item { Text(stringResource(R.string.plugins_no_compatible)) }
        else -> items(d.versions, key = { it.versionId }) { v ->
          VersionRow(
            v = v,
            recommended = v === best,
            busy = st.planning,
            onInstall = { vm.requestInstall(v) },
            onOpen = { v.externalUrl?.let { u -> runCatching { uri.openUri(u) } } },
          )
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun VersionRow(v: VersionOption, recommended: Boolean, busy: Boolean, onInstall: () -> Unit, onOpen: () -> Unit) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (recommended) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f)) {
        Text(v.name.ifBlank { v.number }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
          listOfNotNull(
            v.channel,
            v.number.takeIf { it != v.name && it.isNotBlank() },
            v.published.ifBlank { null },
            v.size.takeIf { it > 0 }?.let { formatSize(it) },
          ).joinToString("  ·  "),
          style = MaterialTheme.typography.labelSmall,
        )
        if (recommended) {
          Text(stringResource(R.string.plugins_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
      }
      when {
        v.downloadable -> Button(onClick = onInstall, enabled = !busy) {
          Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text(stringResource(R.string.action_install))
        }
        v.externalUrl != null -> OutlinedButton(onClick = onOpen) {
          Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(18.dp))
          Spacer(Modifier.width(4.dp))
          Text(stringResource(R.string.plugins_open_browser))
        }
      }
    }
  }
}

// ======================================================================================
// Small shared pieces
// ======================================================================================

@Composable
private fun Avatar(title: String, url: String?, loader: IconLoader?, size: Dp = 44.dp) {
  val bmp by produceState<Bitmap?>(null, url) { value = if (url != null && loader != null) loader.load(url) else null }
  Box(
    Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    val b = bmp
    if (b != null) {
      Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
    } else {
      Text(
        title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}

internal fun formatSize(bytes: Long): String = when {
  bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
  bytes >= 1024 -> "%d KB".format(bytes / 1024)
  else -> "$bytes B"
}

internal fun formatCount(n: Long): String = when {
  n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
  n >= 1_000 -> "%.1fK".format(n / 1_000.0)
  else -> n.toString()
}
