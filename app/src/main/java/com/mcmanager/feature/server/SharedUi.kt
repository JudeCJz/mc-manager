package com.mcmanager.feature.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mcmanager.R
import com.mcmanager.core.model.ServerState
import kotlinx.coroutines.delay

// ---------- state colours / chip ----------

private val GreenState = Color(0xFF43A047)
private val AmberState = Color(0xFFFFA000)
private val BlueState = Color(0xFF1E88E5)

@Composable
fun stateColor(state: ServerState): Color = when (state) {
  ServerState.RUNNING -> GreenState
  ServerState.STARTING, ServerState.STOPPING -> AmberState
  ServerState.INSTALLING -> BlueState
  ServerState.CRASHED -> MaterialTheme.colorScheme.error
  ServerState.STOPPED -> MaterialTheme.colorScheme.outline
}

fun stateLabelRes(state: ServerState): Int = when (state) {
  ServerState.STOPPED -> R.string.state_stopped
  ServerState.INSTALLING -> R.string.state_installing
  ServerState.STARTING -> R.string.state_starting
  ServerState.RUNNING -> R.string.state_running
  ServerState.STOPPING -> R.string.state_stopping
  ServerState.CRASHED -> R.string.state_crashed
}

@Composable
fun StateChip(state: ServerState, modifier: Modifier = Modifier) {
  val c = stateColor(state)
  Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = c.copy(alpha = 0.16f)) {
    Row(
      Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Surface(Modifier.size(8.dp), shape = CircleShape, color = c) {}
      Text(stringResource(stateLabelRes(state)), style = MaterialTheme.typography.labelMedium, color = c)
    }
  }
}

/** Ticks once a second; re-composes only itself. */
@Composable
fun UptimeText(startedAt: Long?, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(startedAt) {
    while (startedAt != null) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }
  Text(formatUptime(startedAt, now), modifier, style = style)
}

// ---------- RAM bar / warning ----------

/** Bar of [usedMb] vs [totalMb] with markers at 50 % (warn) and 65 % (danger). */
@Composable
fun RamBar(usedMb: Int, totalMb: Int, modifier: Modifier = Modifier) {
  val track = MaterialTheme.colorScheme.surfaceVariant
  val warn = AmberState
  val danger = MaterialTheme.colorScheme.error
  val ok = MaterialTheme.colorScheme.primary
  val level = ramLevel(usedMb, totalMb)
  val fill = when (level) {
    RamLevel.OK -> ok
    RamLevel.WARN -> warn
    RamLevel.DANGER -> danger
  }
  val frac = if (totalMb > 0) (usedMb.toFloat() / totalMb).coerceIn(0f, 1f) else 0f
  Canvas(modifier.fillMaxWidth().height(14.dp)) {
    val r = CornerRadius(size.height / 2, size.height / 2)
    drawRoundRect(track, size = size, cornerRadius = r)
    if (frac > 0f) drawRoundRect(fill, size = Size(size.width * frac, size.height), cornerRadius = r)
    val w = 2.dp.toPx()
    drawRect(warn, topLeft = Offset(size.width * 0.5f - w / 2, 0f), size = Size(w, size.height))
    drawRect(danger, topLeft = Offset(size.width * 0.65f - w / 2, 0f), size = Size(w, size.height))
  }
}

/** Shows nothing at [RamLevel.OK]. */
@Composable
fun RamWarning(level: RamLevel, modifier: Modifier = Modifier) {
  if (level == RamLevel.OK) return
  val danger = level == RamLevel.DANGER
  val container = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
  val content = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
  Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Warning, contentDescription = null)
      Text(
        stringResource(if (danger) R.string.ram_warn_danger else R.string.ram_warn_warn),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** RAM slider in 256 MB steps, value shown in the label. */
@Composable
fun RamSlider(label: String, valueMb: Int, minMb: Int, maxMb: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
  val hi = maxOf(maxMb, minMb + 256)
  val steps = ((hi - minMb) / 256 - 1).coerceAtLeast(0)
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(formatMb(valueMb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
      value = valueMb.coerceIn(minMb, hi).toFloat(),
      onValueChange = { v -> onChange((Math.round((v - minMb) / 256f) * 256 + minMb).coerceIn(minMb, hi)) },
      valueRange = minMb.toFloat()..hi.toFloat(),
      steps = steps,
    )
  }
}

/** Max Xmx offered by sliders: total RAM minus 1.5 GB, rounded down to 256. */
fun maxXmxFor(totalRamMb: Int): Int = (((totalRamMb - 1536) / 256) * 256).coerceAtLeast(1024)

// ---------- dropdown ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
  label: String,
  selectedLabel: String,
  options: List<Pair<String, String>>,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth(),
  enabled: Boolean = true,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
    OutlinedTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      enabled = enabled,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
    )
    ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
      options.forEach { (id, text) ->
        DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false })
      }
    }
  }
}

// ---------- JVM presets / GC ----------

/** Presets from res/raw/jvm_presets.json; known ids get translated labels. */
@Composable
fun jvmPresetOptions(): List<Pair<String, String>> {
  val context = androidx.compose.ui.platform.LocalContext.current
  val presets = remember { com.mcmanager.engine.JvmFlags.presets(context) }
  val labels = mapOf(
    "aikar" to stringResource(R.string.jvm_preset_aikar),
    "phone" to stringResource(R.string.jvm_preset_phone),
    "zgc" to stringResource(R.string.jvm_preset_zgc),
    "minimal" to stringResource(R.string.jvm_preset_minimal),
  )
  return presets.map { it.id to (labels[it.id] ?: it.name) }
}

val GcOptions = listOf("G1", "ZGC", "Shenandoah", "Parallel", "Serial")

fun gcOptionPairs(): List<Pair<String, String>> = GcOptions.map { it to it }

// ---------- misc helpers ----------

fun copyToClipboard(context: Context, label: String, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
  val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
  return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context) {
  val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(direct)
  } catch (_: Exception) {
    try {
      context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
  }
}

/** State that re-reads the battery-optimisation status every time the screen resumes. */
@Composable
fun rememberBatteryIgnored(): State<Boolean> {
  val context = LocalContext.current
  val state = remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
  LifecycleResumeEffect(Unit) {
    state.value = isIgnoringBatteryOptimizations(context)
    onPauseOrDispose {}
  }
  return state
}

fun openUrl(context: Context, url: String) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: Exception) {
  }
}
