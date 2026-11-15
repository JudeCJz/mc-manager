package com.mcmanager.engine

import android.content.Context
import java.io.File

/** Environment every java process on Android needs (see docs/PLAN.md). Reuse for any `java` exec. */
object JavaEnv {
  const val TAGFIX_LIB = "libmctagfix.so"

  /** Puts JAVA_HOME, LD_LIBRARY_PATH and LD_PRELOAD (pointer-tag shim) into [pb]'s environment. */
  fun apply(pb: ProcessBuilder, javaHome: File, context: Context) {
    val env = pb.environment()
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    env["JAVA_HOME"] = javaHome.absolutePath
    env["LD_LIBRARY_PATH"] = "${javaHome.absolutePath}/lib:${javaHome.absolutePath}/lib/server:$nativeLibDir"
    env["LD_PRELOAD"] = File(nativeLibDir, TAGFIX_LIB).absolutePath
  }

  /** Java home for a `.../bin/java` binary (parent of bin). */
  fun homeOf(javaBin: File): File = javaBin.absoluteFile.parentFile?.parentFile ?: javaBin.absoluteFile
}
