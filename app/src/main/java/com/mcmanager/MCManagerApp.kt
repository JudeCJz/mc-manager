package com.mcmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mcmanager.feature.backups.BackupScheduler
import com.mcmanager.feature.network.NetworkManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MCManagerApp : Application(), Configuration.Provider {

  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var backupScheduler: BackupScheduler
  @Inject lateinit var networkManager: NetworkManager

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

  override fun onCreate() {
    super.onCreate()
    // Re-register periodic backups and start the UPnP/tunnel watcher (both idempotent).
    backupScheduler.rescheduleAll()
    networkManager.init()
  }
}
