package com.whispertranscriber.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

object WhisperServerDiscovery {
    private const val DEFAULT_PORT = 8090
    private const val DEFAULT_TIMEOUT_MS = 350
    private const val MAX_PARALLEL_PROBES = 32

    suspend fun discover(
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): DiscoveredWhisperServer? = withContext(Dispatchers.IO) {
        val hosts = candidateHosts(localIpv4Addresses())
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)

        coroutineScope {
            hosts.map { host ->
                async {
                    semaphore.withPermit {
                        probe(host, port, timeoutMs)
                    }
                }
            }.awaitAll().filterNotNull().firstOrNull()
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
            if (!body.contains("\"ready\":true") && !body.contains("\"status\":\"ok\"")) return null
            DiscoveredWhisperServer(url)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
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
