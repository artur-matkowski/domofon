# 04 — The gate camera: stills now, video + audio later (milestone M3)

Goal, eventually: the gate camera's live stream, with audio, inside the QML view — over
the VPN. Goal, **already built**: a still picture every few seconds, on the phone *and* on
the car screen, which is the part that answers "is someone at the gate".

Read §1 before touching camera code. It records two constraints invisible in the code:
the camera is **one RTSP URL and nothing else** (a product rule), and decoded video frames
are **never read on the CPU** (not a preference — a crash).

## 0. Prove the stream before writing any code

Always debug the stream *outside* the app first. From your Debian machine:

```bash
ffplay -rtsp_transport tcp "rtsp://user:pass@<camera-ip>:554/<path>"
```

- Note the exact URL/credentials that work — that string goes into the app.
- Check it also works across the VPN (laptop on phone hotspot + VPN).
- If the camera offers a **substream** (lower resolution), note its URL too — on a
  phone screen 720p is plenty and it saves VPN bandwidth and decode latency.

## 1. The still picture — implemented

The car screen can never show QML (Android Auto renders Car App Library templates only),
but a `PaneTemplate` renders one large bitmap. So the camera reaches the head unit as a
still, refreshed every `camera.snapshotSecs` seconds. The phone panel shows the same frame
from the same flow — a `Bitmap` cannot cross the `QtQuickView` property bridge, so
`MainActivity.writeFrame()` drops it in the cache directory and hands QML a `file://` URL
with a changing `?v=` query (QtQuick caches `Image` sources by URL; without the query a new
frame would never repaint).

**The frame comes out of the RTSP stream, and that is a product decision before it is a
technical one.** The obvious alternative is an HTTP snapshot endpoint, and it works — but
this camera answers only at `/ISAPI/Streaming/channels/101/picture` (Hikvision) and only
with Digest auth. Another brand answers somewhere else, or not at all. An app built on that
either ships a table of vendor paths or tells the user to run a restreamer, and neither is
something a stranger installing from Play can do. RTSP is the one address every camera
speaks, the user already has it for their own player, and it carries its own credentials.

> **Rule (Artur, 2026-07-24):** one universal channel. If the app needs a backend or knows
> your camera's brand, it has stopped being publishable software.

An HTTP snapshot URL is still *supported*, as an override for anyone who has a reason —
go2rtc or Frigate already running, or a link that costs less over a metered tunnel. It is
never required. §1.3 covers it.

### 1.1 How a frame is taken safely

This is the part with the scar tissue. The first implementation pointed ExoPlayer at an
`ImageReader` and read `Image.getPlanes()`. MediaCodec is free to hand back buffers that
live only on the GPU, and reading a plane from one is a **native abort inside the
framework** — a SIGABRT, not an exception, so no `try/catch` sees it — which killed the app
about three seconds after every launch on the test phone. There is no way to ask an `Image`
whether reading it is safe first. Full write-up in ch. 10 (`nativeCreatePlanes`).

GPU-only buffers are perfectly fine if you read them *with the GPU*. So `OffscreenTextureReader`:

1. an EGL pbuffer context on the player's own thread — nothing is ever displayed;
2. the decoder renders into a `SurfaceTexture` backed by a `GL_TEXTURE_EXTERNAL_OES` texture;
3. that texture is drawn into an offscreen FBO **sized to the target** — so a 4 MP frame is
   scaled by the GPU during the draw, not decoded at full size and shrunk on the CPU after;
4. `glReadPixels` produces ordinary CPU memory, copied straight into an ARGB_8888 `Bitmap`
   (the byte orders match, so no channel shuffling).

Two details that are easy to get wrong and expensive to debug:

- **`updateTexImage()` must run for every frame**, including the ones you throttle away. It
  is what returns the buffer to the decoder; skip it and the queue fills and the stream
  stalls, which looks exactly like a dead camera.
- **The `SurfaceTexture` transform matrix is mandatory.** It carries the decoder's crop
  rectangle — the coded size is padded up to whole macroblocks — so ignoring it shows the
  padding as a green strip down one edge.

### 1.2 The stream stays open while a screen is watching

Reconnecting per snapshot sounds tidier, but an RTSP handshake plus a keyframe wait is
1–3 s over the VPN, every time; at a ten second interval that is most of the duty cycle
anyway, for worse latency and more churn. What bounds the cost is *when* the grabber runs —
only while the phone UI is foregrounded or a car session is live, exactly like the MQTT
connection.

If bandwidth over the tunnel matters, **use the camera's substream** (on this Hikvision,
`/Streaming/Channels/102`). A 640 px still needs nothing more, and it is the single biggest
lever here.

### 1.3 The optional HTTP snapshot override

Only if you want it. `scripts/find-snapshot-url.sh` finds the endpoint:

```bash
scripts/find-snapshot-url.sh 192.168.1.60 admin     # prompts for the password
scripts/find-snapshot-url.sh --src gate 10.0.0.5 -p 1984   # a go2rtc restream
```

It tries the paths the common vendors use, judges by JPEG magic bytes rather than the
`Content-Type` header (cameras lie; a `200 OK` carrying an HTML error page is the usual
near-miss), and reports the auth scheme. `--help` lists the flags.

Caveats, none of which apply to the RTSP path:

- **Digest auth is not supported.** `HttpURLConnection` speaks Basic only. Hikvision and
  Dahua firmware generally insists on Digest, so the answer there is go2rtc in front — or
  simply leaving the field empty, which is the point of RTSP being the default.
- **Cleartext HTTP had to be permitted app-wide** (`res/xml/network_security_config.xml`),
  because every address here is typed by the user at runtime and cannot be allowlisted at
  build time. Recorded as an accepted residual risk in ch. 11 §3a.
- **Credentials in the URL are ignored by `HttpURLConnection`** — a URL that works in a
  browser goes out unauthenticated and 401s, reading exactly like a wrong password. The
  userinfo is stripped and re-sent as a `Basic` header so the field behaves like the RTSP one.

## 2. QtMultimedia in QML — *not built yet*

Everything from here down is the live-playback route, which nothing implements — the
same `camera.rtspUrl` §1 already reads, played rather than sampled. Sketch, not record:
unlike §1, none of this has met a device.

A note for whenever it is picked up: **audio does not inherit §1's constraint.** The abort
was specific to reading video *frames* on the CPU; playing a stream never touches that path.
Audio-only playback through media3 — the video track disabled, the mirror of what
`RtspFrameSource` does — would work on the phone *and* during an Android Auto session, since
it lives in Kotlin rather than QML. That is the cheaper half of this chapter. Two things to
settle when starting: AudioFocus (an intercom must duck navigation, not fight it), and
whether the camera's codec needs a go2rtc transcode — many speak G.711, which ExoPlayer
will not play.

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

## 3. Latency expectations and tuning

An intercom wants < 1–2 s glass-to-glass. Measure first (wave at the camera, count):

1. **Camera side is the biggest lever**: short GOP / keyframe interval (≤ 1 s), constant
   bitrate, and use the **substream**. Many cameras default to 4 s keyframes — that
   alone adds seconds of startup delay.
2. QtMultimedia exposes few RTSP knobs. If the stream works in `ffplay` with
   `-rtsp_transport tcp` but the app stalls: packet loss over UDP in the tunnel is the
   usual cause; OpenVPN over TCP + RTSP over UDP is a bad combination (see ch. 09).

## 4. If QtMultimedia disappoints — fallbacks (in order)

1. **go2rtc restream** (<https://github.com/AlexxIT/go2rtc>) on the home server: it
   ingests the camera RTSP once and serves clean, fast RTSP to clients — often fixes
   camera-quirk and latency problems with zero app changes (just a new URL).
2. **libVLC for Android** (`org.videolan.android:libvlc-all`): battle-tested RTSP with
   explicit `--network-caching=150`-style knobs. Trade-off: video moves out of QML into
   a Kotlin `SurfaceView`/`TextureView` layered *under or beside* the QtQuickView; QML
   keeps the controls. Pragmatic, works well.

Don't preemptively build fallbacks. Measure, then decide.

## Acceptance tests

**The still (§1), the part that is built.** Phone on mobile data (Wi-Fi off) + OpenVPN
connected, the RTSP address set in Settings →

- the phone panel shows a picture within ~15 s (RTSP handshake + first keyframe), then
  refreshes on the configured cadence;
- the picture is upright and has no green strip down an edge (see §1.1 — those are the two
  ways the GL readback goes wrong, and both are visible at a glance);
- changing *Snapshot interval* changes the cadence with no relaunch;
- the DHU (or the car) shows the same picture in a `PaneTemplate`, gate button beneath;
- with the VPN pulled, both keep the last picture and say the camera is unreachable;
  bringing it back recovers within one retry (30 s) without restarting the app;
- `adb logcat -s Domofon:*` contains no URL, no credential, and no `CLEARTEXT` denial;
- all of the above repeated on a **release** build — R8 is load-bearing in this project
  and debug proves nothing.

✅ **M3 proper passes when:** phone on mobile data + OpenVPN → app shows live gate video
**with audio**, latency acceptable to you, and rotating the phone or backgrounding/resuming
the app doesn't wedge the player.
