package pl.bitforge.domofon.data.camera

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bitforge.domofon.domain.camera.AudioStatus
import pl.bitforge.domofon.domain.config.CameraFeed
import pl.bitforge.domofon.domain.config.CameraSource
import pl.bitforge.domofon.domain.config.DomofonConfig

/**
 * The part of the camera that has no business touching Android: *when* a session exists,
 * which one, and who is still allowed to speak.
 *
 * All four of these are regressions. The picture used to keep showing the old camera after a
 * settings change, and the only cure was a force-stop; the causes were an open that raced the
 * close it replaced (this camera allows exactly one RTSP session) and a retired source whose
 * parting IDLE landed on its replacement's CONNECTING. See docs/troubleshooting.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraFrameGrabberTest {

    private val rtsp = CameraFeed.Rtsp(url = "rtsp://gate/main", audioEnabled = true)

    private fun config(feed: CameraFeed?, snapshotSecs: Int = 10) = DomofonConfig.EMPTY.copy(
        camera = DomofonConfig.EMPTY.camera.copy(
            source = if (feed is CameraFeed.Http) CameraSource.HTTP else CameraSource.RTSP,
            rtspUrl = (feed as? CameraFeed.Rtsp)?.url.orEmpty(),
            snapshotUrl = (feed as? CameraFeed.Http)?.imageUrl.orEmpty(),
            audioUrl = (feed as? CameraFeed.Http)?.audioUrl.orEmpty(),
            audioEnabled = when (feed) {
                is CameraFeed.Rtsp -> feed.audioEnabled
                is CameraFeed.Http -> feed.audioEnabled
                null -> true
            },
            snapshotSecs = snapshotSecs,
        ),
    )

    /**
     * Records the order in which sessions were opened and closed, so "close, then open" can
     * be asserted rather than assumed. [open] fails the run outright if a previous source is
     * still live — that is the invariant the camera itself enforces, and the whole bug.
     */
    private class Sources : CameraFrameGrabber.SourceFactory {
        val log = mutableListOf<String>()
        val opened = mutableListOf<FakeSource>()
        var live: FakeSource? = null

        override fun open(
            feed: CameraFeed,
            intervalMs: () -> Long,
            sink: CameraFrameGrabber.Sink,
        ): FrameSource {
            check(live == null) { "opened a second session while one was still live" }
            val source = FakeSource(feed, intervalMs, sink, this)
            live = source
            opened += source
            log += "open"
            return source
        }
    }

    private class FakeSource(
        val feed: CameraFeed,
        val intervalMs: () -> Long,
        val sink: CameraFrameGrabber.Sink,
        private val owner: Sources,
    ) : FrameSource {
        var started = false
        var closed = false

        override fun start() {
            started = true
            owner.log += "start"
        }

        override fun close() {
            closed = true
            owner.live = null
            owner.log += "close"
        }
    }

    /**
     * The grabber's session scope outlives the surface in production (a teardown that is
     * cancelled half-way leaves a session open at the camera), so it is not `backgroundScope`
     * — that one belongs to the test. It shares the test scheduler, which is what makes
     * `advanceUntilIdle` drive the whole lifecycle; [tearDown] disposes of it.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun TestScope.grabber(config: MutableStateFlow<DomofonConfig>, sources: Sources) =
        CameraFrameGrabber(
            config,
            sources,
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                .also { scopes += it },
        )

    @Test
    fun `a feed change closes the old source before opening the new one`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        val grabber = grabber(config, sources)

        grabber.start()
        advanceUntilIdle()

        config.value = config(rtsp.copy(url = "rtsp://gate/sub"))
        advanceUntilIdle()

        // The check inside Sources.open is the real assertion; this pins the order down.
        assertEquals(listOf("open", "start", "close", "open", "start"), sources.log)
        assertEquals(2, sources.opened.size)
        assertTrue(sources.opened[0].closed)
        assertFalse(sources.opened[1].closed)
        assertEquals("rtsp://gate/sub", (sources.opened[1].feed as CameraFeed.Rtsp).url)
    }

    @Test
    fun `toggling gate audio reopens the session`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        grabber(config, sources).start()
        advanceUntilIdle()

        config.value = config(rtsp.copy(audioEnabled = false))
        advanceUntilIdle()

        assertEquals(2, sources.opened.size)
        assertFalse((sources.opened[1].feed as CameraFeed.Rtsp).audioEnabled)
    }

    @Test
    fun `retuning the snapshot interval does not reopen, and the new value is read live`() =
        runTest {
            val config = MutableStateFlow(config(rtsp, snapshotSecs = 10))
            val sources = Sources()
            grabber(config, sources).start()
            advanceUntilIdle()
            assertEquals(10_000L, sources.opened[0].intervalMs())

            config.value = config(rtsp, snapshotSecs = 3)
            advanceUntilIdle()

            assertEquals(1, sources.opened.size)
            assertEquals(3_000L, sources.opened[0].intervalMs())
        }

    @Test
    fun `a retired source can no longer touch the status or the picture`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        val grabber = grabber(config, sources)
        grabber.start()
        advanceUntilIdle()

        val retired = sources.opened[0].sink
        config.value = config(rtsp.copy(url = "rtsp://gate/sub"))
        advanceUntilIdle()

        // What the old RTSP source used to post from its own thread on the way out, arriving
        // after the replacement had already said CONNECTING.
        retired.status(CameraFrameGrabber.Status.IDLE)
        retired.audioStatus(AudioStatus.ERROR)

        assertEquals(CameraFrameGrabber.Status.CONNECTING, grabber.health.value.frames)
        assertEquals(AudioStatus.NONE, grabber.health.value.audio)

        // The live one still is heard.
        sources.opened[1].sink.status(CameraFrameGrabber.Status.STREAMING)
        assertEquals(CameraFrameGrabber.Status.STREAMING, grabber.health.value.frames)
    }

    @Test
    fun `stop closes the session and a restart does not overlap it`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        val grabber = grabber(config, sources)

        grabber.start()
        advanceUntilIdle()

        grabber.stop()
        // Deliberately without draining in between: this is the phone's onStop/onStart pair
        // when the user comes straight back from Settings, and the teardown is asynchronous.
        grabber.start()
        advanceUntilIdle()

        assertEquals(listOf("open", "start", "close", "open", "start"), sources.log)
        assertTrue(sources.opened[0].closed)
        assertFalse(sources.opened[1].closed)
    }

    @Test
    fun `stop reports idle only once the source is really gone`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        val grabber = grabber(config, sources)
        grabber.start()
        advanceUntilIdle()
        sources.opened[0].sink.status(CameraFrameGrabber.Status.STREAMING)

        grabber.stop()
        advanceUntilIdle()

        assertEquals(CameraFrameGrabber.Status.IDLE, grabber.health.value.frames)
        assertEquals(AudioStatus.NONE, grabber.health.value.audio)
    }

    @Test
    fun `clearing the camera address closes the session and reports idle`() = runTest {
        val config = MutableStateFlow(config(rtsp))
        val sources = Sources()
        val grabber = grabber(config, sources)
        grabber.start()
        advanceUntilIdle()

        config.value = config(null)
        advanceUntilIdle()

        assertTrue(sources.opened[0].closed)
        assertNull(sources.live)
        assertEquals(1, sources.opened.size)
        assertEquals(CameraFrameGrabber.Status.IDLE, grabber.health.value.frames)
    }
}
