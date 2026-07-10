# 04 — RTSP video + audio in QML (milestone M3)

Goal: the gate camera's live stream, with audio, inside the QML view — over the VPN.

## 0. Prove the stream before writing any code

Always debug the stream *outside* the app first. From your Debian machine:

```bash
ffplay -rtsp_transport tcp "rtsp://user:pass@<camera-ip>:554/<path>"
```

- Note the exact URL/credentials that work — that string goes into the app.
- Check it also works across the VPN (laptop on phone hotspot + VPN).
- If the camera offers a **substream** (lower resolution), note its URL too — on a
  phone screen 720p is plenty and it saves VPN bandwidth and decode latency.

## 1. QtMultimedia in QML

Qt 6's FFmpeg backend (the default on Android) handles RTSP with audio. Make sure the
QML project's CMake links `Multimedia` (`find_package(Qt6 ... Multimedia)` /
`target_link_libraries(... Qt6::Multimedia)`) — the module was installed in ch. 01.

Extend `Main.qml`:

```qml
import QtQuick
import QtMultimedia

Rectangle {
    id: root
    color: "#1e1e2e"

    property string gateState: "unknown"
    property string streamUrl: ""          // set from Kotlin — never hardcode creds
    signal commandRequested(string action)

    VideoOutput {
        id: videoOut
        anchors.fill: parent
        fillMode: VideoOutput.PreserveAspectFit
    }

    MediaPlayer {
        id: player
        source: root.streamUrl
        videoOutput: videoOut
        audioOutput: AudioOutput { volume: 1.0 }
        onErrorOccurred: (err, msg) => console.warn("player error:", err, msg)
    }

    onStreamUrlChanged: if (streamUrl !== "") { player.source = streamUrl; player.play() }

    // gate state + buttons from ch. 03 overlay the video:
    Column { /* ... state text + buttons, anchored to the bottom ... */ }
}
```

Kotlin feeds the URL when the QML is ready (store it in `SharedPreferences` /
`DataStore`, entered once in a small settings dialog — not in source):

```kotlin
mainQml.setProperty("streamUrl", settings.rtspUrl)
```

**Lifecycle**: stop the player when the activity pauses, or the stream keeps pulling
bytes through the VPN in the background:

```kotlin
override fun onPause() { super.onPause(); mainQml.setProperty("streamUrl", "") }
override fun onResume() { super.onResume(); mainQml.setProperty("streamUrl", settings.rtspUrl) }
```

(Matching QML: `onStreamUrlChanged` with an empty value → `player.stop()`.)

**Manifest**: `<uses-permission android:name="android.permission.INTERNET"/>` (cleartext
RTSP is fine — it's not HTTP, `usesCleartextTraffic` does not apply to RTSP sockets).

## 2. Latency expectations and tuning

An intercom wants < 1–2 s glass-to-glass. Measure first (wave at the camera, count):

1. **Camera side is the biggest lever**: short GOP / keyframe interval (≤ 1 s), constant
   bitrate, and use the **substream**. Many cameras default to 4 s keyframes — that
   alone adds seconds of startup delay.
2. QtMultimedia exposes few RTSP knobs. If the stream works in `ffplay` with
   `-rtsp_transport tcp` but the app stalls: packet loss over UDP in the tunnel is the
   usual cause; OpenVPN over TCP + RTSP over UDP is a bad combination (see ch. 09).

## 3. If QtMultimedia disappoints — fallbacks (in order)

1. **go2rtc restream** (<https://github.com/AlexxIT/go2rtc>) on the home server: it
   ingests the camera RTSP once and serves clean, fast RTSP to clients — often fixes
   camera-quirk and latency problems with zero app changes (just a new URL).
2. **libVLC for Android** (`org.videolan.android:libvlc-all`): battle-tested RTSP with
   explicit `--network-caching=150`-style knobs. Trade-off: video moves out of QML into
   a Kotlin `SurfaceView`/`TextureView` layered *under or beside* the QtQuickView; QML
   keeps the controls. Pragmatic, works well.

Don't preemptively build fallbacks. Measure, then decide.

## Acceptance test — milestone M3

✅ **M3 passes when:** phone on mobile data (Wi-Fi off) + OpenVPN connected → app shows
live gate video **with audio**, latency acceptable to you, and rotating the phone or
backgrounding/resuming the app doesn't wedge the player.
