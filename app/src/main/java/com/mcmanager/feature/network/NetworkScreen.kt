package com.mcmanager.feature.network

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcmanager.R
import com.mcmanager.core.model.ServerState
import com.mcmanager.core.ui.components.BackButton
import com.mcmanager.core.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
  Row(
    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun SectionDivider() {
  HorizontalDivider(
    Modifier.padding(vertical = 8.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
  )
}

@Composable
private fun Note(text: String) =
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Warn(text: String) =
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked, onChange, enabled = enabled)
  }
}

@Composable
private fun CopyRow(label: String?, value: String, onCopied: () -> Unit = {}) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      if (label != null) Note(label)
      Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
    IconButton(onClick = {
      scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", value))) }
      onCopied()
    }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy)) }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(serverId: Long, onBack: () -> Unit, vm: NetworkViewModel = hiltViewModel()) {
  LaunchedEffect(serverId) { vm.bind(serverId) }
  val server by vm.server.collectAsStateWithLifecycle()
  val runtime by vm.runtime.collectAsStateWithLifecycle()
  val info by vm.info.collectAsStateWithLifecycle()
  val fwd by vm.forward.collectAsStateWithLifecycle()
  val prefs by vm.prefs.collectAsStateWithLifecycle()
  val settings by vm.settings.collectAsStateWithLifecycle()
  val playit by vm.playit.collectAsStateWithLifecycle()
  val ngrok by vm.ngrok.collectAsStateWithLifecycle()
  val diff by vm.latencyDiff.collectAsStateWithLifecycle()

  val snack = remember { SnackbarHostState() }
  val ctx = LocalContext.current
  LaunchedEffect(Unit) {
    vm.messages.collect { m ->
      val text = if (m.arg != null) ctx.getString(m.res, m.arg) else ctx.getString(m.res)
      snack.showSnackbar(text)
    }
  }
  val copiedMsg = stringResource(R.string.ops_copied)
  val scope = rememberCoroutineScope()
  val onCopied: () -> Unit = { scope.launch { snackbar(copiedMsg, snack) } }
  val uriHandler = LocalUriHandler.current

  val s = server
  val stopped = runtime.state == ServerState.STOPPED || runtime.state == ServerState.CRASHED
  val port = s?.port ?: 25565
  val fp = prefs.forServer(serverId)

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.ops_network_title)) },
        navigationIcon = { BackButton(onBack) },
        actions = {
          IconButton(onClick = { vm.refreshInfo() }) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snack) },
  ) { padding ->
    Column(
      Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 1. LOCAL WI-FI SECTION
      SectionHeader(stringResource(R.string.ops_section_local), Icons.Filled.Wifi)
      val i = info
      val kindText = when (i?.kind) {
        ConnKind.WIFI -> stringResource(R.string.ops_conn_wifi)
        ConnKind.MOBILE -> stringResource(R.string.ops_conn_mobile)
        ConnKind.ETHERNET -> stringResource(R.string.ops_conn_ethernet)
        else -> stringResource(R.string.ops_conn_none)
      }
      Note(kindText)
      if (i?.lanIp != null) CopyRow(stringResource(R.string.ops_lan_address), "${i.lanIp}:$port", onCopied)
      else Warn(stringResource(R.string.ops_lan_none))
      if (i?.hotspotIp != null) CopyRow(stringResource(R.string.ops_hotspot_address), "${i.hotspotIp}:$port", onCopied)

      // Port edit
      var portText by rememberSaveable(port) { mutableStateOf(port.toString()) }
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
          modifier = Modifier.weight(1f), singleLine = true, enabled = stopped,
          label = { Text(stringResource(R.string.ops_port_label)) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(onClick = { vm.changePort(portText) }, enabled = stopped && portText != port.toString()) {
          Text(stringResource(R.string.action_save))
        }
      }
      Note(stringResource(if (stopped) R.string.ops_port_note else R.string.ops_port_running))

      // Bedrock notice
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Extension, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ops_bedrock_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      SectionDivider()

      // 2. CUSTOM PUBLIC INGRESS / TUNNEL
      SectionHeader(stringResource(R.string.ops_custom_tunnel_title), Icons.Filled.Public)
      Note(stringResource(R.string.ops_custom_tunnel_intro))
      var customAddrText by rememberSaveable(settings.customPublicAddress) { mutableStateOf(settings.customPublicAddress) }
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = customAddrText,
          onValueChange = { customAddrText = it },
          modifier = Modifier.weight(1f),
          singleLine = true,
          label = { Text(stringResource(R.string.ops_custom_tunnel_label)) },
          placeholder = { Text("dessert-hollywood.tun.ply.gg:25565") },
        )
        Button(
          onClick = { vm.saveCustomPublicAddress(customAddrText) },
          enabled = customAddrText.trim() != settings.customPublicAddress,
        ) {
          Text(stringResource(R.string.action_save))
        }
      }

      SectionDivider()

      // 3. UPNP & REACHABILITY
      SectionHeader(stringResource(R.string.ops_section_public), Icons.Filled.Router)
      Note(stringResource(R.string.ops_upnp_intro))
      SwitchRow(stringResource(R.string.ops_upnp_enable), fp.enabled, !fwd.busy) { vm.setForward(it) }
      SwitchRow(stringResource(R.string.ops_upnp_udp), fp.udp, !fwd.busy) { vm.setUdp(it) }
      SwitchRow(stringResource(R.string.ops_upnp_remove_on_stop), fp.removeOnStop) { vm.setRemoveOnStop(it) }
      if (fwd.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
      when {
        fwd.upnpFound == false -> Warn(stringResource(R.string.ops_upnp_not_found))
        fwd.active -> Text(
          stringResource(R.string.ops_upnp_active, port, if (fwd.leaseSeconds == 0) stringResource(R.string.ops_lease_permanent) else stringResource(R.string.ops_lease_renewed)),
          color = MaterialTheme.colorScheme.tertiary,
          style = MaterialTheme.typography.bodyMedium,
        )
        fp.enabled && !fwd.busy -> Note(stringResource(R.string.ops_upnp_inactive))
      }
      fwd.error?.let { Warn(stringResource(R.string.ops_error_fmt, it)) }

      fwd.routerWanIp?.let { Note(stringResource(R.string.ops_router_ip, it)) }
      fwd.publicV4?.let { Note(stringResource(R.string.ops_public_ip, it)) }
      when (fwd.verdict) {
        Verdict.CGNAT, Verdict.DOUBLE_NAT, Verdict.MISMATCH -> Warn(stringResource(R.string.ops_cgnat_warning))
        Verdict.DIRECT_OK -> {
          Text(stringResource(R.string.ops_reach_ok), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
          fwd.publicV4?.let { CopyRow(stringResource(R.string.ops_share_address), "$it:$port", onCopied) }
        }
        Verdict.UNKNOWN -> {
          if (fwd.publicV4 != null && fwd.upnpFound == false) Note(stringResource(R.string.ops_reach_unknown))
        }
      }
      var showTest by rememberSaveable { mutableStateOf(false) }
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.check() }, enabled = !fwd.busy) { Text(stringResource(R.string.ops_check)) }
        TextButton(onClick = { showTest = true }) { Text(stringResource(R.string.ops_test_outside)) }
      }
      if (showTest) {
        ConfirmDialog(
          title = stringResource(R.string.ops_test_outside),
          text = stringResource(R.string.ops_test_outside_note),
          onConfirm = { showTest = false }, onDismiss = { showTest = false },
        )
      }

      // IPv6
      val v6 = info?.ipv6.orEmpty()
      if (v6.isNotEmpty()) {
        Note(stringResource(R.string.ops_ipv6_title))
        v6.forEach { CopyRow(null, "[$it]:$port", onCopied) }
        Note(stringResource(R.string.ops_ipv6_note))
      }

      SectionDivider()

      // 4. TUNNELS (PLAYIT & NGROK)
      SectionHeader(stringResource(R.string.ops_section_tunnels), Icons.Filled.VpnKey)
      Note(stringResource(R.string.ops_playit_intro, port))

      // Playit status and musl advisory
      AgentStatusBadge(playit)
      Warn(stringResource(R.string.ops_playit_musl_warning))

      // Paste Playit Secret field
      var secretText by rememberSaveable { mutableStateOf("") }
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = secretText,
          onValueChange = { secretText = it },
          modifier = Modifier.weight(1f),
          singleLine = true,
          label = { Text(stringResource(R.string.ops_playit_secret_label)) },
          placeholder = { Text("Paste secret key from playit.gg") },
        )
        Button(
          onClick = { vm.savePlayitSecret(secretText); secretText = "" },
          enabled = secretText.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_save))
        }
      }

      playit.claimUrl?.let { url ->
        Warn(stringResource(R.string.ops_playit_claim))
        Button(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.ops_playit_open_claim)) }
      }
      playit.addresses.forEach { CopyRow(stringResource(R.string.ops_tunnel_address), it, onCopied) }
      if (playit.phase == AgentPhase.RUNNING && playit.addresses.isEmpty()) Note(stringResource(R.string.ops_playit_dashboard))

      val playitOn = playit.phase in listOf(AgentPhase.RUNNING, AgentPhase.STARTING, AgentPhase.NEEDS_CLAIM, AgentPhase.DOWNLOADING)
      Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (playitOn) OutlinedButton(onClick = { vm.stopPlayit() }) { Text(stringResource(R.string.action_stop)) }
        else Button(onClick = { vm.startPlayit() }) { Text(stringResource(R.string.action_start)) }
        TextButton(onClick = { uriHandler.openUri("https://playit.gg/account/tunnels") }) {
          Text(stringResource(R.string.ops_playit_dashboard_btn))
        }
      }
      LogBox(playit.log)

      // ngrok subsection
      SectionDivider()
      Note(stringResource(R.string.ops_ngrok_intro))
      var token by rememberSaveable(settings.ngrokAuthToken) { mutableStateOf(settings.ngrokAuthToken) }
      OutlinedTextField(
        value = token, onValueChange = { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.ops_ngrok_token)) },
        trailingIcon = {
          TextButton(onClick = { vm.saveNgrokToken(token) }, enabled = token.trim() != settings.ngrokAuthToken) {
            Text(stringResource(R.string.action_save))
          }
        },
      )
      val regions = listOf("" to R.string.ops_region_auto, "in" to R.string.ops_region_in, "ap" to R.string.ops_region_ap, "eu" to R.string.ops_region_eu, "us" to R.string.ops_region_us)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        regions.forEach { (code, label) ->
          FilterChip(selected = prefs.ngrokRegion == code, onClick = { vm.setNgrokRegion(code) }, label = { Text(stringResource(label)) })
        }
      }
      AgentStatusBadge(ngrok)
      ngrok.addresses.forEach { CopyRow(stringResource(R.string.ops_tunnel_address), it, onCopied) }
      val ngrokOn = ngrok.phase in listOf(AgentPhase.RUNNING, AgentPhase.STARTING, AgentPhase.DOWNLOADING)
      if (ngrokOn) OutlinedButton(onClick = { vm.stopNgrok() }) { Text(stringResource(R.string.action_stop)) }
      else Button(onClick = { vm.startNgrok() }, enabled = settings.ngrokAuthToken.isNotBlank() && s != null) {
        Text(stringResource(R.string.action_start))
      }
      LogBox(ngrok.log)

      SwitchRow(stringResource(R.string.ops_tunnels_stop_with_servers), prefs.tunnelsStopWithServers) { vm.setTunnelsStop(it) }

      SectionDivider()

      // 5. LOW LATENCY & OPTIMIZATIONS
      SectionHeader(stringResource(R.string.ops_section_tuning), Icons.Filled.Speed)
      Button(onClick = { vm.prepareLatency() }, enabled = s != null) { Text(stringResource(R.string.ops_latency_apply)) }
      Note(stringResource(R.string.ops_latency_restart))
      listOf(R.string.ops_tip_order, R.string.ops_tip_wifi, R.string.ops_tip_lock, R.string.ops_tip_view).forEach {
        Text("- " + stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Spacer(Modifier.height(32.dp))
    }
  }

  val unsetLabel = stringResource(R.string.ops_unset)
  diff?.let { changes ->
    ConfirmDialog(
      title = stringResource(R.string.ops_latency_title),
      text = if (changes.isEmpty()) stringResource(R.string.ops_latency_nothing)
      else changes.joinToString("\n") { "${it.key}: ${it.old ?: unsetLabel} -> ${it.new}" } +
        "\n\n" + stringResource(R.string.ops_latency_restart),
      onConfirm = { if (changes.isEmpty()) vm.dismissLatency() else vm.applyLatency() },
      onDismiss = { vm.dismissLatency() },
    )
  }
}

private suspend fun snackbar(msg: String, host: SnackbarHostState) {
  host.showSnackbar(msg)
}

@Composable
private fun AgentStatusBadge(st: AgentState) {
  val (labelRes, isError) = when (st.phase) {
    AgentPhase.NOT_INSTALLED -> R.string.ops_agent_not_installed to false
    AgentPhase.DOWNLOADING -> R.string.ops_agent_downloading to false
    AgentPhase.NEEDS_CLAIM -> R.string.ops_agent_needs_claim to false
    AgentPhase.STARTING -> R.string.ops_agent_starting to false
    AgentPhase.RUNNING -> R.string.ops_agent_running to false
    AgentPhase.STOPPED -> R.string.ops_agent_stopped to false
    AgentPhase.ERROR -> R.string.ops_agent_error to true
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(labelRes),
      style = MaterialTheme.typography.titleSmall,
      color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
  }
  if (st.phase == AgentPhase.DOWNLOADING) {
    st.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
      ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp))
  }
  st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LogBox(lines: List<String>) {
  if (lines.isEmpty()) return
  Text(
    lines.takeLast(6).joinToString("\n"),
    style = MaterialTheme.typography.labelSmall,
    fontFamily = FontFamily.Monospace,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 6,
    modifier = Modifier.padding(vertical = 2.dp),
  )
}
