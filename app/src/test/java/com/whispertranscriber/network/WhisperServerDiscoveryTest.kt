package com.whispertranscriber.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhisperServerDiscoveryTest {

    @Test
    fun candidatesIncludeSameSubnetPeersAndOwnAddressFirst() {
        val candidates = WhisperServerDiscovery.candidateHosts(listOf("192.168.4.20"))

        assertEquals("192.168.4.20", candidates.first())
        assertTrue(candidates.contains("192.168.4.1"))
        assertTrue(candidates.contains("192.168.4.254"))
        assertFalse(candidates.contains("192.168.4.0"))
        assertFalse(candidates.contains("192.168.4.255"))
    }

    @Test
    fun candidatesIncludeTailscaleSubnetPeers() {
        val candidates = WhisperServerDiscovery.candidateHosts(listOf("100.101.157.10"))

        assertTrue(candidates.contains("100.101.157.1"))
        assertTrue(candidates.contains("100.101.157.254"))
    }

    @Test
    fun candidatesIgnoreLoopbackAndInvalidAddresses() {
        val candidates = WhisperServerDiscovery.candidateHosts(
            listOf("127.0.0.1", "not-an-ip", "10.0.0.8")
        )

        assertFalse(candidates.contains("127.0.0.1"))
        assertFalse(candidates.contains("not-an-ip"))
        assertTrue(candidates.contains("10.0.0.8"))
    }

    @Test
    fun discoverFromHostsKeepsOnlyHealthyServersOnConfiguredPort() = runBlocking {
        val healthy = startHttpServer("""{"status":"ok","ready":true}""")
        val wrongService = startHttpServer("""{"status":"ok","ready":false}""")
        try {
            val discovered = WhisperServerDiscovery.discoverFromHosts(
                hosts = listOf("127.0.0.1", "127.0.0.1"),
                ports = listOf(healthy.port, wrongService.port),
                timeoutMs = 1_000
            )

            assertEquals(listOf("http://127.0.0.1:${healthy.port}"), discovered.map { it.url })
        } finally {
            healthy.close()
            wrongService.close()
        }
    }

    private fun startHttpServer(responseBody: String): TestHttpServer {
        val serverSocket = ServerSocket(0)
        val ready = CountDownLatch(1)
        Thread {
            ready.countDown()
            while (!serverSocket.isClosed) {
                try {
                    val socket = serverSocket.accept()
                    try {
                        val requestLine = socket.getInputStream().bufferedReader().readLine()
                        if (requestLine != null) {
                            val bytes = responseBody.toByteArray()
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().use {
                                it.write(response.toByteArray())
                                it.write(bytes)
                            }
                        }
                    } catch (_: Exception) {
                        // The port-open probe connects and closes without sending an HTTP request.
                    } finally {
                        socket.close()
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        ready.await(1, TimeUnit.SECONDS)
        return TestHttpServer(serverSocket.localPort, serverSocket)
    }

    private data class TestHttpServer(
        val port: Int,
        private val serverSocket: ServerSocket
    ) {
        fun close() {
            serverSocket.close()
        }
    }
}
