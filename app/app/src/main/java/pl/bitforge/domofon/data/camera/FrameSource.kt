package pl.bitforge.domofon.data.camera

/**
 * The seam between [CameraFrameGrabber] and however a picture is actually obtained.
 *
 * The grabber owns *when* a source exists; an implementation owns *how* frames appear. This
 * used to be a structural contract rather than a declared one — every source happened to
 * expose `start()` and `close()`, and the grabber held one as a bare `AutoCloseable` and
 * called `start()` only because each `when` branch knew its concrete type. Declaring it is
 * what lets a new path be a new class plus one branch, with nothing above the façade moving.
 *
 * The contract, sized to what the grabber actually needs:
 *
 * - [start] begins producing and **never throws**. A bad URL, an unreachable host or a codec
 *   the device dislikes arrives as [CameraFrameGrabber.Status.ERROR] on the status callback,
 *   because the grabber holds a lock and a lifecycle that must stay consistent whatever the
 *   configuration says.
 * - [close] is idempotent and never throws. Closing a source that never started, or closing
 *   twice, is always safe — the house RAII rule.
 * - **A source holding a session at the camera must not return from [close] until it has let
 *   go of it**, within a bounded wait. The camera allows exactly one RTSP session, so the
 *   grabber's "close, then open" is only true if close is true; a close that merely *posts*
 *   its teardown turns a settings change into a refused connection and a 30 s backoff. It is
 *   never called on the UI thread (see `CameraFrameGrabber.sessions`). A source with nothing
 *   to hand back — a poll loop over HTTP — may return immediately.
 * - [close] reports no status. Once the grabber has closed a source it has retired it, and
 *   may already have started its replacement; a parting IDLE would land on the new session's
 *   CONNECTING and strand the panel there.
 * - Callbacks arrive on the source's own thread, which is not the caller's. The grabber
 *   funnels them into `StateFlow`s, which are safe to write from anywhere; nothing else may
 *   assume otherwise. Callbacks made after [close] are dropped rather than forbidden — the
 *   grabber pins each source's sink to a generation.
 * - A source emits frames already downscaled to [CameraFrameGrabber.MAX_EDGE]. That cap is a
 *   binder limit on the car pane, not a preference, so it cannot be left to the consumer.
 * - A failing source keeps retrying on its own schedule until it is closed. It must not
 *   emit a null frame or otherwise clear the last good picture: the previous frame stays on
 *   screen with the status saying why, because a gate picture from thirty seconds ago beats
 *   a placeholder.
 *
 * What no implementation may do is read decoded video frames on the CPU. `ImageReader` +
 * `Image.getPlanes()` on a GPU-only decoder buffer is an uncatchable native abort on the test
 * phone — see [OffscreenTextureReader] and docs/troubleshooting.md → `nativeCreatePlanes`.
 */
interface FrameSource : AutoCloseable {

    /** Begin producing frames. Safe to call repeatedly; only the first call does anything. */
    fun start()

    /** Stop and release everything. Idempotent, and safe before [start]. */
    override fun close()
}
