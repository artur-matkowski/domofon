package pl.bitforge.domofon.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionErrorMessagesTest {

    @Test
    fun `null cause is a plain connection loss`() {
        assertEquals("Connection lost", ConnectionErrorMessages.describe(null))
    }

    @Test
    fun `socket errors name themselves`() {
        assertEquals(
            "Cannot resolve the broker address",
            ConnectionErrorMessages.describe(UnknownHostException("x")),
        )
        assertEquals(
            "Broker refused the connection or is unreachable",
            ConnectionErrorMessages.describe(ConnectException()),
        )
        assertEquals(
            "Timed out reaching the broker",
            ConnectionErrorMessages.describe(SocketTimeoutException()),
        )
    }

    @Test
    fun `tls failure points at the setting and port`() {
        assertEquals(
            "TLS handshake failed — check the TLS setting and port",
            ConnectionErrorMessages.describe(SSLHandshakeException("boom")),
        )
    }

    @Test
    fun `the interesting exception is usually wrapped - the chain is walked`() {
        val wrapped = RuntimeException("outer", RuntimeException("mid", UnknownHostException("x")))
        assertEquals("Cannot resolve the broker address", ConnectionErrorMessages.describe(wrapped))
    }

    @Test
    fun `anything named Timeout matches by name - shaded netty classes relocate`() {
        class ShadedConnectTimeoutException : Exception()
        assertEquals(
            "Timed out reaching the broker",
            ConnectionErrorMessages.describe(ShadedConnectTimeoutException()),
        )
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        val e = RuntimeException("loop")
        e.initCause(RuntimeException("inner").also { inner ->
            // A two-node cycle: walking it naively never ends.
            runCatching { inner.initCause(e) }
        })
        // Any answer is fine as long as it returns; the bounded walk falls back to a name.
        ConnectionErrorMessages.describe(e)
    }

    @Test
    fun `unknown leaf falls back to the class name`() {
        class WeirdBrokerQuirk : Exception()
        assertEquals("WeirdBrokerQuirk", ConnectionErrorMessages.describe(WeirdBrokerQuirk()))
    }
}
