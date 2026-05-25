package com.whispertranscriber.network

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

object WhisperServerDiscovery {
    const val DEFAULT_PORT = 8090
    private const val DEFAULT_TIMEOUT_MS = 350
    private const val MAX_PARALLEL_PROBES = 32

    suspend fun discover(
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): DiscoveredWhisperServer? = discoverAll(port, timeoutMs).firstOrNull()

    suspend fun discoverAll(
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): List<DiscoveredWhisperServer> {
        return discoverFromHosts(
            hosts = candidateHosts(localIpv4Addresses()),
            ports = listOf(port),
            timeoutMs = timeoutMs
        )
    }

    suspend fun discoverFromHosts(
        hosts: List<String>,
        ports: List<Int>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): List<DiscoveredWhisperServer> = withContext(Dispatchers.IO) {
        val candidates = hosts.distinct().flatMap { host ->
            ports.distinct().mapNotNull { port ->
                if (port in 1..65535) host to port else null
            }
        }
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)

        coroutineScope {
            candidates.map { (host, port) ->
                async {
                    semaphore.withPermit {
                        probe(host, port, timeoutMs)
                    }
                }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }
    }

    fun candidateHosts(addresses: List<String>): List<String> {
        val ordered = linkedSetOf<String>()
        addresses.mapNotNull(::parseIpv4).forEach { octets ->
            if (octets[0] == 127) return@forEach

            val ownAddress = octets.joinToString(".")
            ordered.add(ownAddress)

            val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
            for (last in 1..254) {
                ordered.add("$prefix.$last")
            }
        }
        return ordered.toList()
    }

    private fun localIpv4Addresses(): List<String> {
        return NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
            }
    }

    private fun probe(host: String, port: Int, timeoutMs: Int): DiscoveredWhisperServer? {
        if (!isPortOpen(host, port, timeoutMs)) return null

        val url = "http://$host:$port"
        val connection = (URL("$url/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            useCaches = false
        }

        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (!isHealthy(body)) return null
            DiscoveredWhisperServer(url)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isHealthy(body: String): Boolean {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val readyElement = json.get("ready")?.takeUnless { it.isJsonNull }
            val ready = readyElement?.asBoolean
            val status = json.get("status")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            ready ?: (status.equals("ok", ignoreCase = true) || status.equals("healthy", ignoreCase = true))
        } catch (_: Exception) {
            false
        }
    }

    private fun parseIpv4(address: String): IntArray? {
        val parts = address.split(".")
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets.toIntArray()
    }
}

data class DiscoveredWhisperServer(
    val url: String
)
