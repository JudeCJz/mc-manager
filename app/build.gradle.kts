plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.hilt)
}

android {
  namespace = "com.mcmanager"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.mcmanager"
    minSdk = 26
    // Deliberately 28: from targetSdk 29 SELinux forbids exec of files in the app data dir
    // (needed for the downloaded JRE). See docs/PLAN.md.
    targetSdk = 28
    versionCode = 1
    versionName = "0.1.0"
    // Only arm64 phones; the JRE we download is arm64 only.
    ndk { abiFilters += "arm64-v8a" }
  }

  // libmctagfix.so: LD_PRELOAD shim that disables bionic heap pointer tagging for the JVM.
  ndkVersion = "27.3.13750724"
  externalNativeBuild {
    cmake { path = file("src/main/cpp/CMakeLists.txt") }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }
  lint {
    disable += "ExpiredTargetSdkVersion"
    abortOnError = false
    checkReleaseBuilds = false
  }
  packaging {
    // Extract .so files to nativeLibraryDir so LD_PRELOAD can point at libmctagfix.so on disk.
    jniLibs { useLegacyPackaging = true }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*,DEPENDENCIES}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

androidComponents {
  onVariants { variant ->
    variant.outputs.forEach { output ->
      val name = android.defaultConfig.versionName
      (output as? com.android.build.api.variant.impl.VariantOutputImpl)
        ?.outputFileName?.set("MCManager-v$name-${variant.buildType}.apk")
    }
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.navigation.compose)

  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.androidx.hilt.work)
  ksp(libs.androidx.hilt.compiler)

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.work.runtime.ktx)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.commons.compress)
  implementation(libs.xz)
  implementation(libs.snakeyaml)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
