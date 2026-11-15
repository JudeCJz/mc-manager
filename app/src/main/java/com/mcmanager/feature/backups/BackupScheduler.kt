package com.mcmanager.feature.backups

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.work.BackupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class BackupScheduler @Inject constructor(
  @ApplicationContext private val context: Context,
  private val servers: ServerRepository,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private fun name(serverId: Long) = "backup-$serverId"

  fun schedule(serverId: Long, hours: Int) {
    if (hours <= 0) {
      cancel(serverId)
      return
    }
    val req = PeriodicWorkRequestBuilder<BackupWorker>(hours.toLong(), TimeUnit.HOURS)
      .setInputData(workDataOf(BackupWorker.KEY_SERVER_ID to serverId))
      .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
      .build()
    WorkManager.getInstance(context)
      .enqueueUniquePeriodicWork(name(serverId), ExistingPeriodicWorkPolicy.UPDATE, req)
  }

  fun cancel(serverId: Long) {
    WorkManager.getInstance(context).cancelUniqueWork(name(serverId))
  }

  /** Re-registers schedules for all servers. Call once at app start (MCManagerApp.onCreate). */
  fun rescheduleAll() {
    scope.launch {
      servers.getAll().forEach { s ->
        if (s.backupIntervalHours > 0) schedule(s.id, s.backupIntervalHours) else cancel(s.id)
      }
    }
  }
}
