# Module: camera (data/camera)

*[Wiki home](../README.md) › modules › camera*

## Responsibility

The camera picture — a downscaled still every few seconds, plus optional live gate audio —
for every surface that wants one, from whichever of two sources the user selected. Live video
is deliberately absent: Android Auto renders templates only (one large bitmap per pane), and
the phone shows the same still.

| Class | Owns |
|---|---|
| `CameraFrameGrabber` | The façade: one `frame` + one `health` flow; opens the source the selected `CameraFeed` asks for, and reopens it when that changes |
| `FrameSource` | The seam every source implements — `start()` + idempotent `close()`, never throws; a source holding a camera session blocks in `close()` until it has let go |
| `CameraFrame` | One frame as both a `Bitmap` (car pane) and JPEG bytes (QML bridge) |
| `RtspFrameSource` | **The default**: media3 ExoPlayer + RTSP, video → GL surface, audio → speaker; one session carries both |
| `HttpCameraSource` | The HTTP path's composite: an image source plus an optional audio source, one lifetime |
| `HttpFrameSource` | The JPEG-endpoint poller (the HTTP path's picture half) |
| `RtspAudioSource` | Audio-only media3 session, no surface (the HTTP path's sound half) |
| `GateAudioFocus` | The focus request both audio paths share: duck other media, never pause |
| `OffscreenTextureReader` | Offscreen EGL context + `glReadPixels` — the only safe frame readback |

## Public API

```kotlin
class CameraFrameGrabber(
    config: StateFlow<DomofonConfig>,
    sources: SourceFactory,              // CameraFrameGrabber.androidSources(context) in production
    sessions: CoroutineScope,            // where opens and closes run, serialised; never the UI thread
) {
    enum class Status { IDLE, CONNECTING, STREAMING, ERROR }
    data class Health(val frames: Status, val audio: AudioStatus)
    class Sink(frame, status, audioStatus)          // what a source reports to
    fun interface SourceFactory { fun open(feed, intervalMs, sink): FrameSource }

    val frame: StateFlow<CameraFrame?>   // newest still, ≤ MAX_EDGE (960 px) long edge
    val health: StateFlow<Health>
    fun start()                          // no-op unconfigured; safe to repeat
    fun stop()                           // queues the close; the last frame is kept
}
```

`AudioStatus` (`domain/camera`) is `NONE | CONNECTING | PLAYING | ERROR`, and describes only
*separately carried* audio — the RTSP path is always `NONE` even while playing sound, because
its audio is a track of the session `Status` already describes. It lives in `domain/` rather
than here because `GatePolicy` words the user-facing line from it and policy may not reach up
into `data/`.

Per-surface instances come from `AppContainer.newCameraGrabber`
([app-container](app-container.md)); the surface starts/stops it with its lifecycle — a
backgrounded surface must not keep pulling frames over the VPN.

## The two paths

The user picks, explicitly ([decision D3](../architecture/decisions.md)). The selection
resolves to a `CameraFeed` (`domain/config`), which carries exactly the fields its path uses:

| `camera.source` | Picture | Audio | Config it reads |
|---|---|---|---|
| `RTSP` (default) | stills pulled from the stream | a track of the same session | `camera.rtspUrl` |
| `HTTP` | `camera.snapshotUrl` polled per interval | `camera.audioUrl`, a separate RTSP session | both, plus the switch |

`Camera.feed` is `null` when the *selected* path's URL is blank, and that is the whole of "is
a camera configured" (`hasPicture`).

## Invariants

1. **Decoded frames are never read on the CPU.** `ImageReader` + `Image.getPlanes()` on a
   GPU-only MediaCodec buffer is an *uncatchable native abort* (`nativeCreatePlanes`),
   fatal on the test phone. Frames come back through `OffscreenTextureReader` (pbuffer
   EGL + FBO + `glReadPixels`), which also does the downscale on the GPU. Permanent —
   [decision D4](../architecture/decisions.md).
2. **Never a second RTSP session *to the camera*.** The camera allows one: the stills stream
   IO-errors every cycle while a separate audio player runs (verified on device). So the RTSP
   path folds audio into its one session. This is a constraint on *concurrency at the camera*,
   not on session count — `RtspAudioSource` is deliberately a second session, aimed at a
   restreamer while the pictures come from somewhere else. Point both halves of the HTTP path
   at the camera itself and you reproduce the original failure exactly.
   [Decision D3a](../architecture/decisions.md).
   **This is why `close()` blocks.** A source holding a session must not return from `close()`
   until it has let go (bounded — 2 s — then a log line), because the caller's next act is
   usually to open a session to the same camera. A close that merely *posts* its teardown made
   every camera-settings change look like nothing happening; see
   [troubleshooting](../troubleshooting.md). The grabber runs every open and close on one
   serialised queue off the UI thread, which is what makes that affordable.
3. **The picture and the sound fail independently on the HTTP path.** A dead audio stream
   never touches `Status`, never clears a frame, and never changes the car pane — stills that
   keep arriving are not an error. It surfaces as one muted line on the phone, worded by
   `GatePolicy.cameraAudioNotice`, and nowhere else.

   3a. **Gate audio ducks other media and never pauses anything, itself included.** Both
   players used to pass `handleAudioFocus = true` with `USAGE_MEDIA`, which requests
   `AUDIOFOCUS_GAIN` — *permanent* focus, the request an app makes when it is the thing the
   user chose to listen to. The system answers it by stopping everything else for good, and a
   permanent loss is not something the loser retries: opening Domofon stopped Spotify and
   closing it brought nothing back (Artur, live testing 2026-07-29). `GateAudioFocus` requests
   `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` by hand instead.

   There is **no one-line version**, and the reason is worth keeping: media3's
   `AudioFocusManager.setAudioAttributes` ends with `checkArgument(focusGain == AUDIOFOCUS_GAIN
   || focusGain == 0, "Automatic handling of audio focus is only available for USAGE_MEDIA and
   USAGE_GAME.")`, and that method is only reached when `handleAudioFocus` is true — so
   switching the usage to one that maps to a ducking request while leaving automatic handling
   on throws at the first frame. Owning the request means owning the losses, and every one of
   them is a **volume change**: this is a live stream, and a paused live stream accumulates a
   backlog it can only shed by seeking, so pausing for a nav prompt means coming back a
   prompt's worth of time behind the gate. A refused request is silence, not an error — the
   picture is the point and the sound is an extra.
4. **Switching source reopens the session but keeps the last frame.** The resolved
   `CameraFeed` is the restart key, so a change to the source or any URL reopens, while a
   change to `camera.snapshotSecs` does not — retuning cadence must not cost an RTSP
   handshake. `camera.audioEnabled` **is** part of the key and therefore does reopen, which is
   the price of the car screen's mute button being the same global setting as the phone's
   ([ui-car](ui-car.md) invariant 4, [D19](../architecture/decisions.md)). During the swap `Status` goes CONNECTING and the old frame stays: clearing it
   would blink the phone panel and, worse, change the car pane's *shape* mid-session, which is
   what makes a head unit dim ([ui-car](ui-car.md)). **The status has to be visible while the
   frame is stale**, or a reopen that never connected is indistinguishable from one that
   worked — the phone panel says so on a badge over the picture.
5. **`MAX_EDGE = 960`** is a binder trade-off, not a style choice: the bitmap crosses the
   binder inside the car template bundle, where a full-resolution frame courts
   `TransactionTooLarge`. If a head unit ever reports that, step down (720 beats the old
   640).
6. **The stream stays open while started.** Reconnecting per snapshot costs 1–3 s of RTSP
   handshake over the VPN each time — most of the duty cycle. The bound on cost is *when*
   this runs (foreground surfaces only), not per-snapshot churn.
7. **Ordering in `releaseAttempt` is load-bearing**: the player must release *before* the
   reader's surface is destroyed — it must stop writing into the surface first. And
   `OffscreenTextureReader.release()` gives back **only what it took**: never `eglTerminate`,
   which is a statement about `EGL_DEFAULT_DISPLAY` — one process-wide handle shared with Qt's
   scene graph — made by the part of the process that owns the least of it.
8. **A closed source says nothing.** Once the grabber has closed a source it has retired it
   and may already have started the replacement, so a parting IDLE would land on the new
   session's CONNECTING and strand the panel there. Sources emit no status from `close()`, and
   the grabber pins each source's sink to a generation it bumps on every teardown — so a
   callback already in flight on the source's own thread is dropped rather than raced.
9. **Errors keep the last good frame.** A gate picture from thirty seconds ago is worth
   more than a placeholder, as long as `health.frames` says ERROR.
10. **A source hands on its own JPEG bytes only when it scaled nothing.** `CameraFrame`
    carries the fetched bytes so the QML bridge can skip a decode/re-encode round trip and
    deliver the server's own quality choice — but only when the response was already within
    `MAX_EDGE`. Above it, the original is dropped: passing it on would ship the
    full-resolution frame the downscale existed to avoid.
11. **Log hygiene**: never the URL, never exception *messages* from this path (both can
    embed inline camera credentials) — codes and class names only. The source *kind* (RTSP /
    HTTP) is fine, and the grabber logs it on every open, so a settings change leaves a trail.

## Gotchas

- `RtspFrameSource` runs everything on its own `HandlerThread` looper (also the player's
  looper and the EGL thread); `start` posts onto it, `close` posts and waits.
  `OffscreenTextureReader` is thread-confined by convention — nothing in it is synchronized,
  deliberately.
- **Publish the `Handler` before posting anything that reads it.** `handler = Handler(t.looper)
  .also { it.post { … } }` reads as one statement but is two: Kotlin evaluates the right-hand
  side first, so the message is enqueued *before* the field is assigned, and `t.looper` has
  already blocked until the loop is running. The player thread can win that race, find a null
  handler and give up silently. Everything either side of that boundary is `@Volatile` for the
  same reason: the `MessageQueue`'s own lock orders what happened *before* a `post`, which is
  precisely not the writes that follow one.
- Watchdog (15 s no-frame) exists because a camera that answers RTSP but sends no video
  looks identical to success from the player's side; retry backoff is 30 s.
- RTP rides **TCP interleaved** (`setForceUseRtpTcp(true)`) — UDP datagrams rarely survive
  the VPN.
- Known operational caveat: raw-stream audio can be **choppy** (media3 renders the
  camera's irregular RTP timing literally). A go2rtc restream URL smooths it and stays
  inside the one-URL model — see [troubleshooting](../troubleshooting.md).
- `HttpFrameSource` speaks Basic auth only (`HttpURLConnection` has no Digest) and moves
  URL userinfo into an `Authorization` header — `HttpURLConnection` silently *ignores*
  userinfo, which otherwise reads as a wrong password. Response bodies are size-capped:
  the URL is user-supplied and might be an endless MJPEG stream. **The endpoint must return
  one complete JPEG per request** — an MJPEG stream is refused by that cap, not consumed.
- `RtspAudioSource` never sets a surface and disables the video track outright, so it decodes
  audio only. Its watchdog and backoff mirror the picture path's (15 s / 30 s) because a
  restreamer that accepts the session but sends no audio looks like success from the player's
  side. Failures are `Log.w` so they survive R8's stripping of the lower levels.
- The grabber's config collector uses **object identity on the owning scope** as a staleness
  token: a swap queued by a collector that `stop()` has since cancelled must not open a source
  nobody owns.
- **`CameraFrameGrabber` takes flows and a factory, not a `ConfigStore` and a `Context`** —
  the same move `GateViewModel` made, and for the same reason. *When* a session exists and
  which one is ordinary logic with no business touching Android; `androidSources()` is the
  only part of the file that does, and it still holds the whole `when`. That is what
  `CameraFrameGrabberTest` drives.
- The grabber's `sessions` scope **outlives the surface on purpose** and is never cancelled by
  `stop()`: a teardown abandoned half-way leaves a session open at the camera, which is the
  entire failure this page keeps coming back to. `stop()` queues the close and returns, so the
  UI thread never blocks; the next `start()` queues behind it.
- The sources take an `intervalMs: () -> Long` rather than a `ConfigStore`. That is what keeps
  cadence live without putting it in the restart key, and it means `data/camera` no longer
  depends on `data/config` at all.

## Related pages

[ui-phone](ui-phone.md) (how a frame crosses to QML) · [ui-car](ui-car.md) ·
[config](config.md) (the source setting and its rows) ·
[decisions D3/D3a/D4](../architecture/decisions.md) ·
[troubleshooting](../troubleshooting.md)
