import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Release signing credentials.
//
// Secrets are never stored in the build script. They are read, in order of preference, from:
//   1. local.properties  (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD,
//                         RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD)  — gitignored
//   2. environment variables of the same names                        — CI friendly
//
// Debug builds never need them. A release build without credentials fails with guidance rather
// than silently producing an unsigned APK.
// ---------------------------------------------------------------------------
val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("local.properties")
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}

fun signingSecret(key: String): String? =
    signingProperties.getProperty(key)
        ?: System.getenv(key)
        ?: signingProperties.getProperty(key.removePrefix("RELEASE_"))
        ?: System.getenv(key.removePrefix("RELEASE_"))

val releaseStoreFilePath = signingSecret("RELEASE_STORE_FILE")
val releaseStorePassword = signingSecret("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSecret("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSecret("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.personal.base"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.personal.base"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            // Absent when credentials are missing, so the release build fails loudly rather
            // than shipping something unsigned.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    testOptions {
      unitTests.isReturnDefaultValues = true
    }

    // The generated App registry is registered through androidComponents above; it is a build
    // artifact and is never committed.
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  // The core library: capabilities, transport, storage, security, UI-agnostic primitives.
  // Apps depend on this; the shell never defines features.
  implementation(project(":core"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Platform extensions
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.compose.material.icons.extended)
}

// ---------------------------------------------------------------------------
// App registry generation.
//
// An App declares itself with @PlatformAppDefinition. The registry is generated from those
// declarations at build time, so adding an App never requires hand-editing this file.
// The generated registry is a build artifact and is never committed.
// ---------------------------------------------------------------------------
val generateAppRegistry = tasks.register<GenerateAppRegistryTask>("generateAppRegistry") {
    sourceDirs.from(files("src/main/java", "src/main/kotlin"))
    outputDirectory.set(layout.buildDirectory.dir("generated/appregistry"))
}

// Fail loudly, and only for release packaging, when signing credentials are absent.
// Values are captured as primitives so the configuration cache stays serializable.
tasks.matching { it.name.contains("Release") && it.name.contains("Package") }.configureEach {
    val signingConfigured = hasReleaseSigning
    val guidance = "Release signing credentials are not configured. " +
        "Add RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and " +
        "RELEASE_KEY_PASSWORD to local.properties or the environment. " +
        "See local.properties.example."
    doFirst {
        if (!signingConfigured) throw GradleException(guidance)
    }
}

androidComponents {
    // Registering through the variants API wires both the source directory and the task
    // dependency, so the registry always exists before Kotlin compiles.
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateAppRegistry,
            GenerateAppRegistryTask::outputDirectory
        )
    }
}

// Task dependency for the generated registry is established by androidComponents above.
