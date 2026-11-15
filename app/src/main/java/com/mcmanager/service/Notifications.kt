package com.mcmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mcmanager.MainActivity
import com.mcmanager.R
import com.mcmanager.core.model.RuntimeState
import com.mcmanager.core.model.ServerState

object Notifications {
  const val CHANNEL_ID = "server"
  const val NOTIFICATION_ID = 1001

  data class Entry(val name: String, val runtime: RuntimeState)

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val ch = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.notif_channel),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = context.getString(R.string.notif_channel_desc)
      setShowBadge(false)
    }
    nm.createNotificationChannel(ch)
  }

  fun stateLabel(context: Context, rt: RuntimeState): String {
    val base = context.getString(
      when (rt.state) {
        ServerState.INSTALLING -> R.string.notif_state_installing
        ServerState.STARTING -> R.string.notif_state_starting
        ServerState.RUNNING -> R.string.notif_state_running
        ServerState.STOPPING -> R.string.notif_state_stopping
        ServerState.CRASHED -> R.string.notif_state_crashed
        ServerState.STOPPED -> R.string.notif_state_stopped
      },
    )
    return if (rt.state == ServerState.RUNNING) {
      base + " · " + context.getString(R.string.notif_players, rt.onlinePlayers.size)
    } else {
      base
    }
  }

  fun build(context: Context, active: List<Entry>): Notification {
    val open = PendingIntent.getActivity(
      context, 0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stopAll = PendingIntent.getService(
      context, 1,
      Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP_ALL),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val lines = active.map { "${it.name} — ${stateLabel(context, it.runtime)}" }
    val title = if (active.isEmpty()) {
      context.getString(R.string.app_name)
    } else {
      context.resources.getQuantityString(R.plurals.notif_title, active.size, active.size)
    }
    val b = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(title)
      .setContentText(lines.firstOrNull().orEmpty())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(open)
      .addAction(0, context.getString(R.string.notif_stop_all), stopAll)
    if (lines.size > 1) {
      val style = NotificationCompat.InboxStyle()
      lines.forEach { style.addLine(it) }
      b.setStyle(style)
    }
    return b.build()
  }
}
