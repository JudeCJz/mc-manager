package com.mcmanager.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mcmanager.core.data.db.AppDatabase
import com.mcmanager.core.data.db.BackupDao
import com.mcmanager.core.data.db.ServerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "mcmanager.db").build()

  @Provides
  fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

  @Provides
  fun provideBackupDao(db: AppDatabase): BackupDao = db.backupDao()

  @Provides
  @Singleton
  fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

  @Provides
  @IoDispatcher
  fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
