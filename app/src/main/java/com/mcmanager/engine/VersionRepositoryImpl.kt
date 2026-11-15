package com.mcmanager.engine

import com.mcmanager.core.engine.VersionRepository
import com.mcmanager.core.model.Engine
import com.mcmanager.engine.providers.FabricProvider
import com.mcmanager.engine.providers.ForgeProvider
import com.mcmanager.engine.providers.MojangApi
import com.mcmanager.engine.providers.PaperProvider
import com.mcmanager.engine.providers.PurpurProvider
import com.mcmanager.engine.providers.ServerProvider
import com.mcmanager.engine.providers.VanillaProvider
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Registry of the engine providers. Spigot has none (not buildable on a phone). */
@Singleton
class ProviderRegistry @Inject constructor(
  paper: PaperProvider,
  purpur: PurpurProvider,
  fabric: FabricProvider,
  vanilla: VanillaProvider,
  forge: ForgeProvider,
) {
  private val all: Map<Engine, ServerProvider> =
    listOf<ServerProvider>(paper, purpur, fabric, vanilla, forge).associateBy { it.engine }

  operator fun get(engine: Engine): ServerProvider? = all[engine]
}

@Singleton
class VersionRepositoryImpl @Inject constructor(
  private val providers: ProviderRegistry,
  private val mojang: MojangApi,
) : VersionRepository {

  override suspend fun mcVersions(engine: Engine, includeSnapshots: Boolean): List<String> =
    providers[engine]?.mcVersions(includeSnapshots).orEmpty()

  override suspend fun latestBuild(engine: Engine, mcVersion: String): String? =
    providers[engine]?.latestBuild(mcVersion)

  override suspend fun requiredJava(mcVersion: String): Int =
    try {
      mojang.javaMajor(mcVersion)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      null
    } ?: McVersion.fallbackJava(mcVersion)
}
