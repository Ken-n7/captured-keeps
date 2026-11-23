import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi") // screenshot tests: renders real Android UI on the laptop JVM
}

android {
    namespace = "com.capturedkeeps.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.capturedkeeps.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.9.1" // pilot
        // Galaxy Tab A11 and current phones are 64-bit ARM; dropping other CPU types keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives outside the repo (~/.captured-keeps). Without it, release builds come out unsigned.
    val signing = File(System.getProperty("user.home"), ".captured-keeps/signing.properties")
        .takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Debug and release install side by side; debug is "Captured Keeps (debug)".
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Captured Keeps (debug)")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            resValue("string", "app_name", "Captured Keeps")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; resValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
