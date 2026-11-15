package com.mcmanager.feature.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.Engine
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.LoadingBox
import com.mcmanager.feature.server.DropdownField
import com.mcmanager.feature.server.RamSlider
import com.mcmanager.feature.server.RamWarning
import com.mcmanager.feature.server.jvmPresetOptions
import com.mcmanager.feature.server.maxXmxFor
import com.mcmanager.feature.server.openUrl
import com.mcmanager.feature.server.ramLevel

private const val EULA_URL = "https://aka.ms/MinecraftEULA"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateServerScreen(onDone: (Long) -> Unit, onBack: () -> Unit) {
  val vm: CreateServerViewModel = hiltViewModel()
  val ui by vm.ui.collectAsStateWithLifecycle()

  LaunchedEffect(ui.doneId) { ui.doneId?.let(onDone) }
  val handleBack: () -> Unit = { if (!vm.back()) onBack() }
  BackHandler(onBack = handleBack)

  val stepCount = CreateStep.entries.size
  val canNext = when (ui.step) {
    CreateStep.ENGINE -> ui.engine != null
    CreateStep.VERSION -> ui.version != null
    CreateStep.DETAILS -> ui.detailsValid
    CreateStep.EULA -> ui.eula
    CreateStep.INSTALL -> false
  }

  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = {
            Column {
              Text(stringResource(R.string.create_title))
              Text(
                stringResource(R.string.create_step_of, ui.step.ordinal + 1, stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          navigationIcon = { if (!ui.installing) BackButton(handleBack) },
        )
        LinearProgressIndicator(progress = { (ui.step.ordinal + 1f) / stepCount }, modifier = Modifier.fillMaxWidth())
      }
    },
    bottomBar = {
      if (ui.step != CreateStep.INSTALL) {
        Surface(tonalElevation = 3.dp) {
          Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = vm::next, enabled = canNext) {
              Text(stringResource(if (ui.step == CreateStep.EULA) R.string.create_install else R.string.create_next))
            }
          }
        }
      }
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      when (ui.step) {
        CreateStep.ENGINE -> EngineStep(ui, vm)
        CreateStep.VERSION -> VersionStep(ui, vm)
        CreateStep.DETAILS -> DetailsStep(ui, vm)
        CreateStep.EULA -> EulaStep(ui, vm)
        CreateStep.INSTALL -> InstallStep(ui, vm)
      }
    }
  }
}

@Composable
private fun EngineStep(ui: CreateUi, vm: CreateServerViewModel) {
  Column(
    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(stringResource(R.string.create_engine_title), style = MaterialTheme.typography.titleMedium)
    Engine.entries.forEach { e ->
      val selected = ui.engine == e
      Card(
        onClick = { vm.selectEngine(e) },
        enabled = e.supported,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
          containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
          disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
      ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(e.label, style = MaterialTheme.typography.titleMedium)
              if (e.experimental) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                  Text(
                    stringResource(R.string.engine_experimental),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }
            Text(
              stringResource(engineDescription(e)),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (!e.supported) Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
          else RadioButton(selected = selected, onClick = null)
        }
      }
    }
  }
}

private fun engineDescription(e: Engine): Int = when (e) {
  Engine.PAPER -> R.string.engine_desc_paper
  Engine.PURPUR -> R.string.engine_desc_purpur
  Engine.FABRIC -> R.string.engine_desc_fabric
  Engine.FORGE -> R.string.engine_desc_forge
  Engine.VANILLA -> R.string.engine_desc_vanilla
  Engine.SPIGOT -> R.string.engine_desc_spigot
}

@Composable
private fun VersionStep(ui: CreateUi, vm: CreateServerViewModel) {
  val engine = ui.engine
  val filtered = ui.versions.filter { it.contains(ui.search.trim(), ignoreCase = true) }
  Column(Modifier.fillMaxSize()) {
    Column(Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.create_version_title, engine?.label.orEmpty()), style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
        value = ui.search,
        onValueChange = vm::setSearch,
        label = { Text(stringResource(R.string.create_search_versions)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      if (engine == Engine.VANILLA || engine == Engine.FABRIC) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.create_snapshots), Modifier.weight(1f))
          Switch(checked = ui.includeSnapshots, onCheckedChange = vm::setSnapshots)
        }
      }
    }
    when {
      ui.versionsLoading -> LoadingBox(Modifier.fillMaxSize(), message = stringResource(R.string.loading))
      ui.versionsError != null -> Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(stringResource(R.string.create_versions_error), style = MaterialTheme.typography.titleSmall)
        if (ui.versionsError.isNotBlank()) {
          Text(ui.versionsError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = vm::loadVersions) { Text(stringResource(R.string.action_retry)) }
      }
      filtered.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.create_versions_empty), style = MaterialTheme.typography.bodyMedium)
      }
      else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(filtered, key = { it }) { v ->
          ListItem(
            headlineContent = { Text(v) },
            trailingContent = { RadioButton(selected = ui.version == v, onClick = null) },
            modifier = Modifier.clickable { vm.selectVersion(v) },
          )
        }
      }
    }
  }
}

@Composable
private fun DetailsStep(ui: CreateUi, vm: CreateServerViewModel) {
  val maxXmx = maxXmxFor(ui.totalRamMb)
  val gmOptions = listOf(
    "survival" to stringResource(R.string.gm_survival),
    "creative" to stringResource(R.string.gm_creative),
    "adventure" to stringResource(R.string.gm_adventure),
    "hardcore" to stringResource(R.string.gm_hardcore),
  )
  val presets = jvmPresetOptions()
  Column(
    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.create_details_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
      value = ui.name,
      onValueChange = vm::setName,
      label = { Text(stringResource(R.string.create_name)) },
      singleLine = true,
      isError = ui.nameTaken,
      supportingText = { if (ui.nameTaken) Text(stringResource(R.string.create_name_taken)) },
      modifier = Modifier.fillMaxWidth(),
    )
    val portUsed = ui.portNumber?.let { it in ui.existingPorts } == true
    OutlinedTextField(
      value = ui.port,
      onValueChange = vm::setPort,
      label = { Text(stringResource(R.string.create_port)) },
      singleLine = true,
      isError = ui.portNumber == null,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      supportingText = {
        when {
          ui.portNumber == null -> Text(stringResource(R.string.create_port_invalid))
          portUsed -> Text(stringResource(R.string.create_port_in_use))
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    RamSlider(stringResource(R.string.create_xmx), ui.xmx, 512, maxXmx, vm::setXmx)
    RamSlider(stringResource(R.string.create_xms), ui.xms, 256, ui.xmx, vm::setXms)
    RamWarning(ramLevel(ui.runningXmxMb + ui.xmx, ui.totalRamMb))
    OutlinedTextField(
      value = ui.seed,
      onValueChange = vm::setSeed,
      label = { Text(stringResource(R.string.create_seed)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    DropdownField(
      label = stringResource(R.string.create_gamemode),
      selectedLabel = gmOptions.first { it.first == ui.gamemode }.second,
      options = gmOptions,
      onSelect = vm::setGamemode,
    )
    if (ui.gamemode == "hardcore") {
      Text(stringResource(R.string.create_hardcore_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownField(
      label = stringResource(R.string.create_jvm_preset),
      selectedLabel = presets.firstOrNull { it.first == ui.jvmPreset }?.second ?: ui.jvmPreset,
      options = presets,
      onSelect = vm::setJvmPreset,
    )
  }
}

@Composable
private fun EulaStep(ui: CreateUi, vm: CreateServerViewModel) {
  val context = LocalContext.current
  Column(
    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.create_eula_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.create_eula_text), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { openUrl(context, EULA_URL) }) {
      Icon(Icons.Filled.OpenInNew, contentDescription = null)
      Text(stringResource(R.string.create_eula_link), Modifier.padding(start = 8.dp))
    }
    Row(
      Modifier.fillMaxWidth().clickable { vm.setEula(!ui.eula) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = ui.eula, onCheckedChange = vm::setEula)
      Text(stringResource(R.string.create_eula_accept), style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun InstallStep(ui: CreateUi, vm: CreateServerViewModel) {
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (ui.installError != null) {
      Text(stringResource(R.string.create_install_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
      if (ui.installError.isNotBlank()) {
        Text(ui.installError, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
      }
      Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::retryInstall) { Text(stringResource(R.string.action_retry)) }
        if (ui.createdId != null) {
          OutlinedButton(onClick = vm::deleteCreated) { Text(stringResource(R.string.create_delete_server)) }
        }
      }
    } else {
      Text(stringResource(R.string.create_installing, ui.name), style = MaterialTheme.typography.titleMedium)
      Text(
        ui.installStep.ifBlank { stringResource(R.string.loading) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
      )
      val f = ui.installFraction
      if (f != null) LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
      else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
  }
}
