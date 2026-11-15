package com.mcmanager.core.net

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

const val USER_AGENT = "MCManager/0.1.0 (android)"

@Module
@InstallIn(SingletonComponent::class)
object NetModule {

  @Provides
  @Singleton
  fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
      chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }
    .build()

  @Provides
  @Singleton
  fun provideJson(): Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
}
