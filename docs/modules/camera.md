# Module: camera (data/camera)

*[Wiki home](../README.md) › modules › camera*

## Responsibility

The camera picture — a downscaled still every few seconds, plus optional live gate audio —
for every surface that wants one. Live video is deliberately absent: Android Auto renders
templates only (one large bitmap per pane), and the phone shows the same still.

| Class | Owns |
|---|---|
| `CameraFrameGrabber` | The façade: one `frame: StateFlow<Bitmap?>` + `status: StateFlow<Status>`; picks the source per start |
| `RtspFrameSource` | **The default**: media3 ExoPlayer + RTSP, video → GL surface, audio → speaker; one session carries both |
| `HttpFrameSource` | The optional JPEG-endpoint override poller |
| `OffscreenTextureReader` | Offscreen EGL context + `glReadPixels` — the only safe frame readback |

## Public API

```kotlin
class CameraFrameGrabber(context, configStore) {
    enum class Status { IDLE, CONNECTING, STREAMING, ERROR }
    val frame: StateFlow<Bitmap?>       // newest still, ≤ MAX_EDGE (960 px) long edge
    val status: StateFlow<Status>
    fun start()                          // no-op unconfigured; safe to repeat
    fun stop()                           // closes the session; the last frame is kept
}
```

Per-surface instances come from `AppContainer.newCameraGrabber`
([app-container](app-container.md)); the surface starts/stops it with its lifecycle — a
backgrounded surface must not keep pulling frames over the VPN.

## Invariants

1. **Decoded frames are never read on the CPU.** `ImageReader` + `Image.getPlanes()` on a
   GPU-only MediaCodec buffer is an *uncatchable native abort* (`nativeCreatePlanes`),
   fatal on the test phone. Frames come back through `OffscreenTextureReader` (pbuffer
   EGL + FBO + `glReadPixels`), which also does the downscale on the GPU. Permanent —
   [decision D4](../architecture/decisions.md).
2. **One RTSP session carries stills *and* audio.** The camera refuses a second RTSP
   connection (verified on device: the stills stream IO-errors every cycle while a
   separate audio player runs). Audio is gated by `camera.audioEnabled`, ducks under
   navigation (`handleAudioFocus = true`), and decodes nothing at all when off.
3. **The snapshot override wins when set** — someone who filled in a snapshot URL had a
   reason; silently preferring the stream would make the field look broken.
4. **`MAX_EDGE = 960`** is a binder trade-off, not a style choice: the bitmap crosses the
   binder inside the car template bundle, where a full-resolution frame courts
   `TransactionTooLarge`. If a head unit ever reports that, step down (720 beats the old
   640).
5. **The stream stays open while started.** Reconnecting per snapshot costs 1–3 s of RTSP
   handshake over the VPN each time — most of the duty cycle. The bound on cost is *when*
   this runs (foreground surfaces only), not per-snapshot churn.
6. **Ordering in `releaseAttempt` is load-bearing**: the player must release *before* the
   reader's surface is destroyed — it must stop writing into the surface first.
7. **Errors keep the last good frame.** A gate picture from thirty seconds ago is worth
   more than a placeholder, as long as `status` says ERROR.
8. **Log hygiene**: never the URL, never exception *messages* from this path (both can
   embed inline camera credentials) — codes and class names only.

## Gotchas

- `RtspFrameSource` runs everything on its own `HandlerThread` looper (also the player's
  looper and the EGL thread); `start`/`close` merely post onto it. `OffscreenTextureReader`
  is thread-confined by convention — nothing in it is synchronized, deliberately.
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
  the URL is user-supplied and might be an endless MJPEG stream.

## Related pages

[ui-phone](ui-phone.md) (how a frame crosses to QML) · [ui-car](ui-car.md) ·
[decisions D3/D4](../architecture/decisions.md) · [troubleshooting](../troubleshooting.md)
