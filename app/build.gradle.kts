plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val signPath = System.getenv("MOUSEHOLE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val signStorePass = System.getenv("MOUSEHOLE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val signAlias = System.getenv("MOUSEHOLE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val signKeyPass = System.getenv("MOUSEHOLE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val persistentSigning = listOf(signPath, signStorePass, signAlias, signKeyPass).all { it != null }
android {
    namespace = "com.alssanbox.mousehole"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.alssanbox.mousehole"
        minSdk = 26
        targetSdk = 35
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }
    signingConfigs {
        if (persistentSigning) {
            create("persistentDebug") {
                storeFile = file(signPath!!)
                storePassword = signStorePass
                keyAlias = signAlias
                keyPassword = signKeyPass
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (persistentSigning) signingConfig = signingConfigs.getByName("persistentDebug")
        }
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:master-SNAPSHOT")
}
