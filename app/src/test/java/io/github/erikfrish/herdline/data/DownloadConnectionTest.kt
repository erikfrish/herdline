package io.github.erikfrish.herdline.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cancel ends a download stalled on the network now, not after the connection's timeout. */
class DownloadConnectionTest {
    /**
     * A host that takes one connection, answers [reply] to its request, then
     * sends nothing more and keeps it open. It takes no other connection, so
     * a client that reconnects is refused rather than stalled again.
     */
    private class StalledHost(private val reply: String) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url = URL("http://127.0.0.1:${server.localPort}/herdline.apk")

        /** Counted down once the request was read and [reply] flushed. */
        val answered = CountDownLatch(1)
        private var client: Socket? = null
        private val accept = Thread {
            runCatching {
                val socket = server.accept()
                client = socket
                server.close()
                socket.getInputStream().read(ByteArray(1024))
                socket.getOutputStream().apply { write(reply.toByteArray()); flush() }
                answered.countDown()
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            server.close()
            client?.close()
            accept.join(1_000)
        }
    }

    /** Opens, attaches and reads [host]'s file to the end, as the updater's download does. */
    private suspend fun download(host: StalledHost, live: DownloadConnection) = withContext(Dispatchers.IO) {
        val connection = host.url.openConnection() as HttpURLConnection
        connection.readTimeout = 60_000
        try {
            live.attach(connection)
            connection.responseCode
            connection.inputStream.use { input -> while (input.read(ByteArray(8 * 1024)) >= 0) Unit }
        } finally {
            live.detach()
            connection.disconnect()
        }
    }

    @Test
    fun cancelEndsADownloadStalledBeforeTheHeadersOrInTheBody() = runBlocking {
        val stalls = listOf(
            "" to "waiting for the headers",
            "HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\npartial" to "the body",
        )
        for ((reply, where) in stalls) {
            StalledHost(reply).use { host ->
                val live = DownloadConnection()
                val running = async(Dispatchers.IO) { runCatching { download(host, live) } }
                assertTrue("$where: never reached the host", host.answered.await(5, TimeUnit.SECONDS))
                running.cancel()
                live.close()
                // The read waits 60 s for the stalled host unless Cancel closes the connection.
                withTimeout(5_000) { running.join() }
                assertTrue("$where: still running after Cancel", running.isCompleted)
            }
        }
    }

    @Test
    fun aConnectionAttachedAfterCancelEndsInsteadOfBlocking() {
        StalledHost("").use { host ->
            val connection = host.url.openConnection() as HttpURLConnection
            val outcome = runCatching {
                runBlocking {
                    coroutineContext.job.cancel()
                    DownloadConnection().attach(connection)
                    connection.responseCode
                }
            }
            connection.disconnect()
            assertTrue("attach let a cancelled download go on: $outcome", outcome.exceptionOrNull() is CancellationException)
            assertTrue("a request went out after Cancel", host.answered.count == 1L)
        }
    }
}
