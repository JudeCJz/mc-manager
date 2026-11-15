package com.mcmanager.core.util

import android.app.ActivityManager
import android.content.Context

object RamUtils {
  private fun info(context: Context): ActivityManager.MemoryInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
  }

  fun totalRamMb(context: Context): Int = (info(context).totalMem / (1024 * 1024)).toInt()

  fun availRamMb(context: Context): Int = (info(context).availMem / (1024 * 1024)).toInt()
}
