import java.util.Properties

plugins {
    id("com.android.application")
    id("org.qtproject.qt.gradleplugin") version "1.4"
}

// Signing only. No application configuration is read at build time any more: broker
// credentials, topics, home coordinates and the RTSP URL all live in the on-device
// settings store, because a published APK is a public artifact and `strings` on it is not
// a difficult attack.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile") != null

// Version comes from git, never hand-edited (see scripts/build-release.sh and docs/11 §4).
//   versionCode = commit count — monotonic as long as history only grows, which is exactly
//                 what Play requires of every upload.
//   versionName = `git describe` for debug/dev builds; the release script overrides it with
//                 the clean semver it computes from Conventional Commits (-PversionName=X.Y.Z).
// Both fall back to a -P property so Android Studio and a .git-less checkout still build.
fun git(vararg a: String): String = try {
    ProcessBuilder(listOf("git", *a)).directory(rootDir).redirectErrorStream(true)
        .start().inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "" }

val gitVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitVersionName = (findProperty("versionName") as String?)
    ?: git("describe", "--tags", "--always", "--dirty").ifBlank { "0.0.0" }

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
        versionCode = gitVersionCode
        versionName = gitVersionName

        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Pinned debug key. A stable keystore at the Gradle root (gitignored via *.keystore)
        // so every debug build signs identically no matter who runs it — artur in-tree or the
        // bitforge agent in a scratchpad copy. Without a pin each user/machine auto-generates
        // its own random ~/.android/debug.keystore, and the next install across identities
        // fails INSTALL_FAILED_UPDATE_INCOMPATIBLE, forcing an uninstall that wipes the
        // Keystore-encrypted broker password and camera URL. Absent (fresh clone / CI) it
        // falls back to AGP's default keystore, so a checkout without the file still builds.
        getByName("debug") {
            val debugKeystore = rootProject.file("domofon-debug.keystore")
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 on, for the first time. This is a behavioural change as much as a size
            // one: Qt and the shaded Netty inside the HiveMQ client both reach for classes
            // reflectively, so proguard-rules.pro is load-bearing and the release build
            // must be re-tested on device, not assumed to work because debug does.
            isMinifyEnabled = true

            // Resource shrinking stays OFF, and this is not caution — it is a known
            // failure. QtLoader resolves its bootstrap data with Resources.getIdentifier(),
            // i.e. by name, with no R-class reference for AAPT to follow: the arrays
            // qt_libs / load_local_libs / bundled_libs and the strings use_local_qt_libs,
            // bundle_local_qt_libs, system_libs_prefix, fatal_error_msg. The shrinker sees
            // them unreferenced and strips them, and the app dies at launch with an
            // UnsatisfiedLinkError. Keep rules do not help — only res/raw/keep.xml does.
            // Turn this on together with that file, and only after re-testing on device.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // ~100 KB of x86_64 Linux epoll native code inside the HiveMQ shaded jar.
            // Netty ships it for JVM servers; on Android it is unreachable dead weight.
            excludes += "META-INF/native/**"
        }
    }
}

// Qt Gradle Plugin 1.4 wires its AAR-generation task (QtBuildTask) into the *debug* compile
// tasks (compileDebugKotlin/JavaWithJavac) but not the *release* ones — for release it only
// hooks the later assemble/collect/lintVital tasks. On a clean release build the Kotlin
// compiler therefore runs before the Qt AAR (QtQuickView, QtQmlStatus, …) is generated, and
// every org.qtproject.* reference is unresolved. Debug is unaffected; release fails only when
// the aars/ dir wasn't already populated by a previous build. Wire the release compile tasks
// to QtBuildTask ourselves so the AAR always exists first. Lazy on purpose: the AGP variant
// compile tasks and the Qt task are all registered after this script block evaluates.
tasks.matching {
    it.name == "compileReleaseKotlin" || it.name == "compileReleaseJavaWithJavac"
}.configureEach { dependsOn("QtBuildTask") }

dependencies {
    // 1.18.0, not 1.19.0: core 1.19.0 declares minCompileSdk=37 and would hard-fail
    // against compileSdk 36.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")

    // The settings screen. Chosen over Compose because AGP 9's built-in Kotlin support and
    // the Compose compiler plugin do not currently coexist comfortably in this project,
    // and a preference screen is exactly what PreferenceFragmentCompat is for.
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Android Auto. app-projected is what makes the app show up on a projected head unit.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    // MQTT. The -shaded artifact relocates its Netty copy; the plain one collides with
    // whatever else drags Netty in. See docs/05.
    //
    // 1.3.17, not the 1.3.5 this project started on: 1.3.5 bundles Netty 4.1.118, which
    // carries 24 open advisories. One of them matters directly now that the app can use
    // TLS — CVE-2026-50010, where wrapping a plain TrustManager silently disables
    // hostname verification, which is exactly the shape of a homelab broker with a
    // self-signed certificate. The 1.3.x line is patch-only, so the API is unchanged.
    implementation("com.hivemq:hivemq-mqtt-client-shaded:1.3.17")

    // Geofencing (ch. 08).
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // RTSP, for the camera still (CameraFrameGrabber -> RtspFrameSource). media3 rather than
    // libVLC: it is the maintained AndroidX stack and costs ~2–3 MB against libVLC's ~40 MB
    // of native code on top of an already Qt-sized APK.
    //
    // What is *not* safe with it is reading decoded frames through an ImageReader — that is
    // a native abort on GPU-only buffers (docs/10 -> nativeCreatePlanes). Frames come back
    // through an offscreen GL context instead; see OffscreenTextureReader.
    //
    // 1.10.1 declares minCompileSdk=36 — exactly this project's compileSdk, so the next
    // media3 minor may not build here until compileSdk moves; same trap as core-ktx 1.19.0.
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.1")
}
