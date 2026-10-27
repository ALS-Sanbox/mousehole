plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.alssanbox.mousehole"
    compileSdk = 36
    defaultConfig { applicationId = "com.alssanbox.mousehole"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // AUSBC CameraActivity inherits from AppCompatActivity.
    implementation("androidx.appcompat:appcompat:1.7.1")
    // JitPack master snapshot is a moving dependency. Pin a tested release before production.
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:master-SNAPSHOT")
}
