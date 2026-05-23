package com.whispertranscriber.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
