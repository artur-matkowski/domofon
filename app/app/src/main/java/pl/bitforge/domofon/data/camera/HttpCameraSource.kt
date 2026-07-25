package pl.bitforge.domofon.data.camera

/**
 * The HTTP camera path: polled JPEGs for the picture, a separate stream for the sound.
 *
 * The RTSP path gets both out of one session, so it needs no composition. This one is two
 * independent halves that happen to share a lifetime, and this class is that lifetime — one
 * owner, one close path, so the grabber above still holds exactly one resource whichever
 * source is selected.
 *
 * The halves are deliberately *not* coupled to each other. A failing audio stream does not
 * stop the stills and does not change the frame status, and no-audio-configured is an
 * ordinary configuration rather than a degraded one: stills without sound is what you get
 * when the switch is off or no audio URL was ever set.
 */
class HttpCameraSource(
    private val image: FrameSource,
    /** Null when there is nothing to play — the switch is off, or no URL was set. */
    private val audio: RtspAudioSource?,
) : FrameSource {

    @Synchronized
    override fun start() {
        // Picture first: it is the half the user is waiting to see, and the audio session's
        // handshake should not sit in front of the first frame.
        image.start()
        audio?.start()
    }

    /** Idempotent, like both halves. Closing twice, or before [start], is safe. */
    @Synchronized
    override fun close() {
        // Reverse order of acquisition, the house rule. Nothing here depends on the other,
        // but the day one of them does, the ordering is already right.
        audio?.close()
        image.close()
    }
}
