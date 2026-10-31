import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.akoway.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.akoway.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // Phones only; keeps the APK small
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // The release key lives outside the repo (D:/akoway-keys); every update must be signed with it
    val keys = file("D:/akoway-keys/keystore.properties")
    signingConfigs {
        if (keys.exists()) create("release") {
            val p = Properties().apply { keys.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.brotli:dec:0.1.2")
}
