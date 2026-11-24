plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "com.karel.radarhud"; compileSdk = 34
  defaultConfig { applicationId = "com.karel.radarhud"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.compose.ui:ui:1.7.0")
  implementation("androidx.compose.material3:material3:1.3.0")
  implementation("androidx.compose.material:material-icons-extended:1.7.0")
  implementation("androidx.camera:camera-camera2:1.3.4")
  implementation("androidx.camera:camera-lifecycle:1.3.4")
  implementation("androidx.camera:camera-view:1.3.4")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.android.gms:play-services-location:21.3.0")
}
