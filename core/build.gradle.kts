plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.personal.base.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // The UI kit and the App contract (@Composable AppContent) live in core,
        // so Apps can build native-looking interfaces without the shell.
        compose = true
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  api(composeBom)

  // Core Android dependencies
  api(libs.androidx.core.ktx)
  api(libs.androidx.lifecycle.runtime.ktx)

  // Compose UI kit — exposed to Apps so their interfaces look native by construction.
  api(libs.androidx.compose.ui)
  api(libs.androidx.compose.material3)
  api(libs.androidx.compose.material.icons.extended)

  // Serialization
  api(libs.kotlinx.serialization.json)

  // Capabilities reach background work
  implementation(libs.androidx.work.runtime.ktx)

  // Real QR and barcode encode/decode
  implementation(libs.zxing.core)

  // Local tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}