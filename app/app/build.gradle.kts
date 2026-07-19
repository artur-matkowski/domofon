import java.util.Properties

plugins {
    id("com.android.application")
    id("org.qtproject.qt.gradleplugin") version "1.4"
}

// Broker credentials live in local.properties (gitignored) — this repo is public, so they
// must never reach git. They land in BuildConfig instead of a settings UI; ch. 04 moves
// them into the real settings store alongside the RTSP URL.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localProp(key: String, fallback: String): String = localProps.getProperty(key) ?: fallback

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

        buildConfigField("String", "MQTT_HOST", "\"${localProp("mqtt.host", "")}\"")
        buildConfigField("int", "MQTT_PORT", localProp("mqtt.port", "1883"))
        buildConfigField("String", "MQTT_USER", "\"${localProp("mqtt.user", "")}\"")
        buildConfigField("String", "MQTT_PASS", "\"${localProp("mqtt.pass", "")}\"")
    }

    buildFeatures { buildConfig = true }

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

    // MQTT. The -shaded artifact relocates its Netty copy; the plain one collides with
    // whatever else drags Netty in. See docs/05.
    implementation("com.hivemq:hivemq-mqtt-client-shaded:1.3.5")

    // Geofencing (ch. 08).
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
