package com.mcmanager.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mcmanager.core.data.ServerRepository
import com.mcmanager.feature.backups.BackupManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class BackupWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val backups: BackupManager,
  private val servers: ServerRepository,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val id = inputData.getLong(KEY_SERVER_ID, -1L)
    if (id < 0) return Result.success()
    val server = servers.get(id) ?: return Result.success()
    if (!File(server.dir).isDirectory) return Result.success()
    val r = backups.createBackup(id, "auto", auto = true)
    return if (r.isSuccess) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
  }

  companion object {
    const val KEY_SERVER_ID = "serverId"
  }
}
