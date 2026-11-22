plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.gatsmod.cut"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gatsmod.cut"
        minSdk = 26
        targetSdk = 35
        // CI passes these from the release tag / run number; local builds use the defaults.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
    }
    // One permanent key for every build, so each new APK installs as an update
    // over the old one (no uninstall). Never change or delete keystore/gatsmod-release.jks.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/gatsmod-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "gatsmodcut2026"
            keyAlias = "gatsmod"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "gatsmodcut2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    // on-device person segmentation for Remove BG
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // offline speech recognition for Auto captions
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
