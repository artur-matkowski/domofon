plugins {
    id("com.android.application")
    id("org.qtproject.qt.gradleplugin") version "1.4"
}

android {
    namespace = "pl.bitforge.domofon"
    compileSdk = 36

    // r27c — the exact NDK Qt 6.11 is built against. Pinned so AGP cannot pick up the
    // release-candidate NDK that sdkmanager also offers; a mismatch shows up as
    // UnsatisfiedLinkError at app start, not as a build error.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "pl.bitforge.domofon"
        minSdk = 28          // Qt for Android's floor
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Android Auto. app-projected is what makes the app show up on a projected head unit.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
}
