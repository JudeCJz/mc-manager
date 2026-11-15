package com.mcmanager.engine

import android.content.Context
import com.mcmanager.R
import com.mcmanager.core.data.db.ServerEntity
import com.mcmanager.core.engine.JavaRuntimeManager
import com.mcmanager.core.engine.ServerInstaller
import com.mcmanager.core.model.Engine
import com.mcmanager.core.model.InstallProgress
import com.mcmanager.engine.providers.InstallEnv
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ServerInstallerImpl @Inject constructor(
  @ApplicationContext private val context: Context,
  private val providers: ProviderRegistry,
  private val java: JavaRuntimeManager,
) : ServerInstaller {

  override suspend fun install(
    server: ServerEntity,
    build: String?,
    onProgress: (InstallProgress) -> Unit,
  ): String {
    if (server.engine == Engine.SPIGOT) {
      throw UnsupportedOperationException(context.getString(R.string.eng_spigot_unsupported))
    }
    val provider = providers[server.engine]
      ?: throw UnsupportedOperationException("${server.engine.label} is not supported")
    val dir = File(server.dir)
    withContext(Dispatchers.IO) { dir.mkdirs() }
    val env = InstallEnv(dir, onProgress) { s, p -> java.javaFor(s, p) }
    try {
      return provider.install(server, build, env)
    } catch (e: CancellationException) {
      throw e
    }
  }

  override suspend fun updateAvailable(server: ServerEntity): String? {
    val provider = providers[server.engine] ?: return null
    return try {
      provider.updateAvailable(server)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }
}
