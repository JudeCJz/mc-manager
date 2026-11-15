package com.mcmanager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.core.model.ServerState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that keeps the process (and the wake/wifi locks) alive while servers run.
 * It stops itself when no server is active. Swiping the app away does NOT stop servers.
 */
@AndroidEntryPoint
class ServerService : Service() {

  @Inject lateinit var controller: ServerControllerImpl
  @Inject lateinit var repo: ServerRepository

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null
  private var lastStartId = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    Notifications.ensureChannel(this)
    startForeground(Notifications.NOTIFICATION_ID, Notifications.build(this, emptyList()))
    scope.launch {
      combine(controller.runtimes, repo.observeAll(), controller.pendingRestarts) { runtimes, servers, pending ->
        val list = servers.mapNotNull { s ->
          val rt = runtimes[s.id] ?: return@mapNotNull null
          if (rt.state.isActive()) Notifications.Entry(s.name, rt) else null
        }
        list to pending
      }.collectLatest { (active, pending) ->
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Notifications.NOTIFICATION_ID, Notifications.build(this@ServerService, active))
        if (active.isNotEmpty() || pending > 0) {
          acquireLocks()
        } else {
          // Small grace period: a restart may flip the state to active again right away.
          delay(1500)
          releaseLocks()
          @Suppress("DEPRECATION")
          stopForeground(true)
          stopSelf(lastStartId)
        }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    if (intent?.action == ACTION_STOP_ALL) {
      CoroutineScope(Dispatchers.IO).launch { controller.stopAll() }
    }
    return START_STICKY
  }

  /** Intentionally empty: servers must survive the app being swiped from recents. */
  override fun onTaskRemoved(rootIntent: Intent?) = Unit

  override fun onDestroy() {
    scope.cancel()
    releaseLocks()
    val anyActive = controller.runtimes.value.values.any { it.state.isActive() }
    if (anyActive) {
      runBlocking { withTimeoutOrNull(35_000) { controller.stopAll() } }
    }
    super.onDestroy()
  }

  private fun acquireLocks() {
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mcmanager:server").apply {
        setReferenceCounted(false)
        acquire()
      }
    }
    if (wifiLock == null) {
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
      wifiLock = wm?.createWifiLock(mode, "mcmanager:server")?.apply {
        setReferenceCounted(false)
        acquire()
      }
    }
  }

  private fun releaseLocks() {
    runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    wakeLock = null
    runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    wifiLock = null
  }

  companion object {
    const val ACTION_STOP_ALL = "com.mcmanager.action.STOP_ALL"
  }
}

internal fun ServerState.isActive(): Boolean = this != ServerState.STOPPED && this != ServerState.CRASHED
