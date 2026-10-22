plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

/** Gradle property (-P or ~/.gradle/gradle.properties) > environment variable > default. */
fun signingValue(prop: String, env: String, default: String): String =
  (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() } ?: default

android {
  namespace = "com.peyton.tvlauncher"
  compileSdk = 34
  defaultConfig {
    applicationId = "com.peyton.tvlauncher"; minSdk = 26; targetSdk = 34; versionCode = (findProperty("vc") as String?)?.toInt() ?: 8; versionName = (findProperty("vn") as String?) ?: "4.3"
  }
  signingConfigs {
    // Every release MUST be signed with the same key as v1..v4.2 (cert SHA-256 65f0d4cb...4a4d113f), or the
    // in-app QR updater / Android refuse the update. See RELEASING.md. Values come from -P / ~/.gradle/gradle.properties
    // (tvlauncher.storeFile, tvlauncher.storePassword, tvlauncher.keyAlias, tvlauncher.keyPassword) or env vars
    // (TVL_STORE_FILE, TVL_STORE_PASSWORD, TVL_KEY_ALIAS, TVL_KEY_PASSWORD), defaulting to the standard Android
    // debug keystore at ~/.android/debug.keystore with its well-known public defaults.
    create("same") {
      storeFile = file(signingValue("tvlauncher.storeFile", "TVL_STORE_FILE", System.getProperty("user.home") + "/.android/debug.keystore"))
      storePassword = signingValue("tvlauncher.storePassword", "TVL_STORE_PASSWORD", "android")
      keyAlias = signingValue("tvlauncher.keyAlias", "TVL_KEY_ALIAS", "androiddebugkey")
      keyPassword = signingValue("tvlauncher.keyPassword", "TVL_KEY_PASSWORD", "android")
    }
  }
  buildTypes {
    getByName("release") { isMinifyEnabled = false; isDebuggable = false; signingConfig = signingConfigs.getByName("same") }
  }
  buildFeatures { compose = true; buildConfig = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17"; freeCompilerArgs += listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true") }
  lint { abortOnError = false; checkReleaseBuilds = false }
  testOptions { unitTests { isIncludeAndroidResources = true; all { it.systemProperty("roborazzi.test.record", "true"); it.maxHeapSize = "2g"; it.setForkEvery(1L) } } }
}
// Robolectric/Compose tests need ui-test-manifest (debugImplementation only), so they only run on the debug variant.
// Without this, `./gradlew test` also runs testReleaseUnitTest, which fails with "Unable to resolve activity ... ComponentActivity".
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }
dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.06.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.activity:activity-compose:1.9.0")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
  implementation("androidx.savedstate:savedstate-ktx:1.2.1")
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")
  implementation("org.nanohttpd:nanohttpd:2.3.1")
  implementation("com.google.zxing:core:3.5.3")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.13")
  testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
  testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
