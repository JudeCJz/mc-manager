package com.mcmanager.feature.files

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
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
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import com.mcmanager.core.ui.components.EmptyState
import com.mcmanager.core.ui.components.LoadingBox
import java.text.DateFormat
import java.util.Date

private sealed interface FileDialog {
  data object NewFolder : FileDialog
  data object NewFile : FileDialog
  data class Rename(val e: FileEntry) : FileDialog
  data class Delete(val e: FileEntry) : FileDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(serverId: Long, startPath: String, onBack: () -> Unit, onEditFile: (String) -> Unit) {
  val vm: FilesViewModel = hiltViewModel()
  LaunchedEffect(serverId) { vm.init(serverId, startPath) }
  val st by vm.state.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  val snack = remember { SnackbarHostState() }
  var dialog by remember { mutableStateOf<FileDialog?>(null) }
  var menuOpen by remember { mutableStateOf(false) }
  var exportTarget by remember { mutableStateOf<FileEntry?>(null) }
  val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

  LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

  val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    if (uris.isNotEmpty()) vm.upload(uris, overwrite = false)
  }
  val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
    val e = exportTarget
    if (uri != null && e != null) vm.export(e, uri)
    exportTarget = null
  }

  BackHandler(enabled = st.path.isNotEmpty()) { vm.up() }

  Scaffold(
    snackbarHost = { SnackbarHost(snack) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.files_title)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = { vm.up() }, enabled = st.path.isNotEmpty()) {
            Icon(Icons.Filled.ArrowUpward, stringResource(R.string.files_up))
          }
          IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
          Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(menuOpen, { menuOpen = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_folder)) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = { menuOpen = false; dialog = FileDialog.NewFolder },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.files_new_file)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                onClick = { menuOpen = false; dialog = FileDialog.NewFile },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.files_upload)) },
                leadingIcon = { Icon(Icons.Filled.Upload, null) },
                onClick = { menuOpen = false; uploadLauncher.launch(arrayOf("*/*")) },
              )
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      Breadcrumbs(st.path, onOpen = { vm.open(it) })
      if (st.serverRunning) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
          Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
              stringResource(R.string.files_running_banner),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.padding(start = 12.dp),
            )
          }
        }
      }
      if (st.busy != null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
          Text(st.busy!!, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          val p = st.progress
          if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
          else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      }
      when {
        st.serverMissing -> EmptyState(stringResource(R.string.files_server_missing), icon = Icons.Filled.FolderOff)
        st.loading && st.entries.isEmpty() -> LoadingBox()
        st.entries.isEmpty() -> EmptyState(stringResource(R.string.files_empty), icon = Icons.Filled.Folder)
        else -> {
          val listState = rememberLazyListState()
          LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(st.entries, key = { it.rel }) { e ->
              FileRow(
                e,
                subtitle = if (e.isDir) dateFmt.format(Date(e.modified))
                else Formatter.formatShortFileSize(ctx, e.size) + "  ·  " + dateFmt.format(Date(e.modified)),
                onClick = { if (e.isDir) vm.open(e.rel) else vm.checkEditable(e, onEditFile) },
                onRename = { dialog = FileDialog.Rename(e) },
                onDelete = { dialog = FileDialog.Delete(e) },
                onExport = { exportTarget = e; exportLauncher.launch(e.name) },
              )
            }
          }
        }
      }
    }
  }

  when (val d = dialog) {
    FileDialog.NewFolder -> NameDialog(R.string.files_new_folder, "", { dialog = null }) { vm.createFolder(it); dialog = null }
    FileDialog.NewFile -> NameDialog(R.string.files_new_file, "", { dialog = null }) { vm.createFile(it); dialog = null }
    is FileDialog.Rename -> NameDialog(R.string.action_rename, d.e.name, { dialog = null }) { vm.rename(d.e, it); dialog = null }
    is FileDialog.Delete -> ConfirmDialog(
      title = stringResource(R.string.confirm_title),
      text = stringResource(if (d.e.isDir) R.string.files_delete_folder_msg else R.string.files_delete_file_msg, d.e.name),
      onConfirm = { vm.delete(d.e); dialog = null },
      onDismiss = { dialog = null },
      confirmLabel = stringResource(R.string.action_delete),
    )
    null -> Unit
  }

  st.conflict?.let { c ->
    ConfirmDialog(
      title = stringResource(R.string.files_overwrite_title),
      text = stringResource(R.string.files_overwrite_msg, c.names.joinToString(", ")),
      onConfirm = { vm.upload(c.uris, overwrite = true) },
      onDismiss = { vm.cancelConflict() },
      confirmLabel = stringResource(R.string.files_overwrite),
    )
  }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
  val parts = if (path.isEmpty()) emptyList() else path.split('/')
  val root = stringResource(R.string.files_root)
  LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    item {
      TextButton(onClick = { onOpen("") }) { Text(root) }
    }
    parts.forEachIndexed { i, p ->
      item {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
          TextButton(onClick = { onOpen(parts.take(i + 1).joinToString("/")) }) { Text(p) }
        }
      }
    }
  }
}

private fun iconFor(e: FileEntry) = when {
  e.isDir -> Icons.Filled.Folder
  else -> when (e.name.substringAfterLast('.', "").lowercase()) {
    "jar" -> Icons.Filled.DataObject
    "zip", "gz", "tar", "xz", "7z", "mca", "dat" -> Icons.Filled.FolderZip
    "png", "jpg", "jpeg", "gif", "webp" -> Icons.Filled.Image
    "yml", "yaml", "json", "json5", "toml", "properties", "cfg", "conf" -> Icons.Filled.Settings
    "txt", "log", "md" -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
  }
}

@Composable
private fun FileRow(
  e: FileEntry,
  subtitle: String,
  onClick: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onExport: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    leadingContent = {
      Icon(
        iconFor(e), null,
        tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    supportingContent = { Text(subtitle, maxLines = 1) },
    trailingContent = {
      Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
        DropdownMenu(menu, { menu = false }) {
          DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
          if (!e.isDir) {
            DropdownMenuItem(text = { Text(stringResource(R.string.files_export)) }, onClick = { menu = false; onExport() })
          }
          DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
        }
      }
    },
  )
}

@Composable
private fun NameDialog(title: Int, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
  var text by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(title)) },
    text = {
      OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.files_name)) })
    },
    confirmButton = {
      TextButton(onClick = { onOk(text.trim()) }, enabled = FileOps.isValidName(text.trim())) {
        Text(stringResource(R.string.action_ok))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}
