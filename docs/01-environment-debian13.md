# 01 — Dev environment on Debian 13 (milestone M0)

Everything is installed under your home directory except a few apt packages. Versions
below were current in July 2026; the rule of thumb: **Qt 6.8+ is required** (Qt Quick for
Android / `QtQuickView`), and use exactly the **NDK version your Qt release documents**
(Qt 6.11 → NDK r27c) to avoid symbol mismatches.

## 1. Base packages

```bash
sudo apt update
sudo apt install openjdk-21-jdk git cmake ninja-build \
                 mosquitto-clients ffmpeg \
                 python3-venv pipx \
                 android-sdk-platform-tools-common
```

- `openjdk-21-jdk` — required by Qt 6.11 for Android and fine for Gradle/Android Studio.
  **Verify it, don't assume it.** Debian's `openjdk-21-jre` gets pulled in by other
  packages and looks identical to `java -version`, but it has no compiler, and Gradle
  fails much later with *"does not provide the required capabilities: [JAVA_COMPILER]"*:

  ```bash
  javac -version                       # must print a version, not "command not found"
  ls /usr/lib/jvm/*/bin/javac          # at least one hit
  ```
- `ninja-build` is optional: ninja already ships inside `~/Android/Sdk/cmake/<ver>/bin/`
  and `~/Qt/Tools/Ninja/`. Put one on `PATH` (or set `qtNinjaPath`) if you skip the apt package.
- `mosquitto-clients` (`mosquitto_pub`/`mosquitto_sub`) and `ffmpeg` (`ffplay`) — your
  acceptance-test tools for chapters 02 and 04.
- `android-sdk-platform-tools-common` — installs the udev rules so `adb` can talk to
  your phone without root. Make sure your user is in `plugdev`:

```bash
sudo usermod -aG plugdev $USER   # log out/in afterwards
```

## 2. Android Studio + SDK

1. Download the Linux tarball from <https://developer.android.com/studio> and extract:

   ```bash
   tar -xzf android-studio-*-linux.tar.gz -C ~/
   ~/android-studio/bin/studio.sh   # first run → setup wizard installs the SDK
   ```

2. In the setup wizard accept the default SDK location `~/Android/Sdk`.
3. Open **Settings → Languages & Frameworks → Android SDK** and install:
   - **SDK Platforms**: Android 16 (API 36). Note `android-36` and `android-36.1` are
     *different packages*; `compileSdk = 36` needs `android-36` specifically.
   - **SDK Tools**: *Android SDK Build-Tools*, *Android SDK Platform-Tools*,
     *Android SDK Command-line Tools*, **NDK (Side by side) → version 27.2.12479018**
     (r27c — the one Qt 6.11 requires), *CMake*.

   ⚠️ The SDK manager also offers newer NDKs that are **release candidates** — it lists
   `30.0.14904198 rc1` without an obvious warning. A wrong NDK doesn't fail the build; it
   fails at app start with `UnsatisfiedLinkError`. Pin it explicitly (ch. 03) and install
   the right one from the CLI to be sure:

   ```bash
   sdkmanager "ndk;27.2.12479018" "platforms;android-36"
   ```
4. Accept licenses and set environment variables — add to `~/.profile`:

   ```bash
   export ANDROID_HOME=$HOME/Android/Sdk
   export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin
   ```

   ```bash
   sdkmanager --licenses
   ```

## 3. Qt for Android

Two options — the online installer is easier, `aqtinstall` is scriptable. Either way you
need **both** the Android target and the Linux desktop host tools of the *same* version.

**Option A — Qt Online Installer** (needs a free Qt account,
<https://www.qt.io/download-qt-installer-oss>):
select the latest Qt 6.x (≥ 6.8) with components:
- *Android* (at minimum `arm64-v8a` — that's your phone)
- *Desktop* (gcc_64 host tools; the installer usually enforces this)
- *Additional Libraries → Qt Multimedia*
- *Qt Creator* is optional — Android Studio is the primary IDE in this project; Qt
  Creator is handy for standalone QML preview.

**Option B — aqtinstall:**

```bash
pipx install aqtinstall
aqt install-qt linux android 6.11.0 android_arm64_v8a -m qtmultimedia --autodesktop -O ~/Qt
```

(`--autodesktop` pulls the matching desktop host tools automatically.)

Either way note your Qt path — it must be the **versioned** directory (`~/Qt/6.11.1`, not
`~/Qt`). The Qt Gradle plugin in ch. 03 needs it.

The online installer also drops the examples on disk at
`~/Qt/Examples/Qt-<version>/`, which the acceptance test below uses.

## 4. Qt Tools for Android Studio plugin

In Android Studio: **Settings → Plugins → Marketplace** → search **"Qt Tools for Android
Studio"** → install. It provides *New Qt Project…* / *Import Qt Project…* and a QML
language server.

**Where the actions actually are:** under **File → New**. That menu only exists when a
project is open, so from the Welcome screen the plugin looks like it isn't installed.
Open any project first. (The same applies to its settings page, which is a *project*
configurable: **Settings → Languages & Frameworks → Qt**.) Since plugin 5.0 the Qt Path
field was removed from the new/import dialog; the path is written into `local.properties`.

Docs: <https://doc.qt.io/qtgradleplugin/> and
<https://doc.qt.io/qt-6/qtquick-for-android.html>.

## 5. Phone setup

1. **Settings → About phone** → tap *Build number* 7× → developer mode.
2. **Developer options** → enable *USB debugging*.
3. Plug in via USB, accept the fingerprint dialog, verify:

   ```bash
   adb devices        # must show your device as "device", not "unauthorized"
   ```

## 6. Desktop Head Unit (DHU) — Android Auto emulator for ch. 07

Install now so it's ready later:

1. `sdkmanager "extras;google;auto"` (or SDK Manager → SDK Tools → *Android Auto Desktop
   Head Unit Emulator*). Binary lands in `~/Android/Sdk/extras/google/auto/`.
2. DHU needs SDL2 and PortAudio at runtime:

   ```bash
   sudo apt install libsdl2-2.0-0 libportaudio2
   ```

   If it still fails to start later, run `ldd ~/Android/Sdk/extras/google/auto/desktop-head-unit`
   and install whatever is missing.
3. On the phone: install **Android Auto** (Play Store), open its settings → tap
   *Version* 10× → enable developer mode. You will use *Start head unit server* +
   `adb forward tcp:5277 tcp:5277` in ch. 07.

## Acceptance test — milestone M0

Prove the whole chain (Qt + NDK + SDK + device) with a stock Qt example, before any
project code exists. **You do not need to clone anything** — the Qt installer already put
the exact example on disk, and it's the same architecture this project uses (Kotlin host
+ `QtQuickView`):

```
~/Qt/Examples/Qt-6.11.1/platforms/android/qtquickview_kotlin/   # Gradle project
~/Qt/Examples/Qt-6.11.1/platforms/android/qtquickview/          # sibling QML+CMake project
```

Two traps before it will build:

- **Its `qtPath` is a relative path** (`../../../../../../6.11.1`) that only resolves from
  inside the Qt install. Copy the example and it breaks. Either build it in place, or copy
  the whole `platforms/android/` directory and switch to an absolute path.
- **It declares AGP 9.0.0 *and* `org.jetbrains.kotlin.android`**, which AGP 9 rejects.
  Delete the Kotlin plugin line from both `build.gradle.kts` files.

Open `qtquickview_kotlin` in Android Studio (**File → Open**), let it generate the Gradle
wrapper, then press **Run** with your phone selected. From a terminal the equivalent is:

```bash
cd ~/Qt/Examples/Qt-6.11.1/platforms/android/qtquickview_kotlin
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # the Qt plugin ignores ANDROID_HOME
gradle wrapper --gradle-version 9.4.1                 # Qt ships no gradlew
./gradlew installDebug
```

A successful debug APK is around 65 MB — most of it is Qt.

✅ **M0 passes when a QML "Hello" screen renders on your physical phone.**
Tick it off in [README.md](README.md). Build problems? → [10-troubleshooting.md](10-troubleshooting.md).
