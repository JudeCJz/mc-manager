package com.mcmanager.di

import com.mcmanager.core.engine.JavaRuntimeManager
import com.mcmanager.core.engine.ServerController
import com.mcmanager.core.engine.ServerInstaller
import com.mcmanager.core.engine.VersionRepository
import com.mcmanager.engine.JavaRuntimeManagerImpl
import com.mcmanager.engine.ServerInstallerImpl
import com.mcmanager.engine.VersionRepositoryImpl
import com.mcmanager.service.ServerControllerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindings {
  @Binds abstract fun javaRuntimeManager(impl: JavaRuntimeManagerImpl): JavaRuntimeManager
  @Binds abstract fun versionRepository(impl: VersionRepositoryImpl): VersionRepository
  @Binds abstract fun serverInstaller(impl: ServerInstallerImpl): ServerInstaller
  @Binds abstract fun serverController(impl: ServerControllerImpl): ServerController
}
