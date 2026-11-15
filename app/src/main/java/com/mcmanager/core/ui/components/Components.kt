package com.mcmanager.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mcmanager.R

@Composable
fun ConfirmDialog(
  title: String,
  text: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  confirmLabel: String = stringResource(R.string.action_ok),
  dismissLabel: String = stringResource(R.string.action_cancel),
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
  )
}

/** Centered spinner, optionally with a [message] and determinate [progress] (0..1). */
@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize(), message: String? = null, progress: Float? = null) {
  Box(modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (progress != null) CircularProgressIndicator(progress = { progress }) else CircularProgressIndicator()
      if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  )
}

@Composable
fun EmptyState(
  title: String,
  modifier: Modifier = Modifier.fillMaxSize(),
  subtitle: String? = null,
  icon: ImageVector = Icons.Filled.Inbox,
  action: (@Composable () -> Unit)? = null,
) {
  Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
      Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
      if (subtitle != null) {
        Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      if (action != null) {
        Spacer(Modifier.height(8.dp))
        action()
      }
    }
  }
}

/** Back arrow for TopAppBar `navigationIcon`. */
@Composable
fun BackButton(onBack: () -> Unit) {
  IconButton(onClick = onBack) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
  }
}

/** Foundation placeholder body: Scaffold + TopAppBar with back + "Coming soon". Owners replace the screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComingSoonScaffold(title: String, onBack: (() -> Unit)? = null) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (onBack != null) BackButton(onBack) },
      )
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
      Text(stringResource(R.string.coming_soon))
    }
  }
}
