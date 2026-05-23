package com.whispertranscriber.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateClientTest {

    @Test
    fun parseManifestReadsReleaseFields() {
        val manifest = UpdateManifest.parse(
            """
                {
                  "versionCode": 42,
                  "versionName": "1.0.42",
                  "commit": "abc1234",
                  "apkUrl": "https://example.com/app.apk",
                  "sizeBytes": 12345,
                  "sha256": "64ec88ca00b268e5ba1a35678a1b5316d212f4f366b2477232534a8aeca37f3c"
                }
            """.trimIndent()
        )

        assertEquals(42, manifest.versionCode)
        assertEquals("1.0.42", manifest.versionName)
        assertEquals("abc1234", manifest.commit)
        assertEquals("https://example.com/app.apk", manifest.apkUrl)
        assertEquals(12345, manifest.sizeBytes)
    }

    @Test
    fun newerThanComparesOnlyVersionCode() {
        val manifest = UpdateManifest(
            versionCode = 2,
            versionName = "older-looking-name",
            commit = "abc1234",
            apkUrl = "https://example.com/app.apk",
            sizeBytes = 1,
            sha256 = null
        )

        assertTrue(manifest.isNewerThan(1))
        assertFalse(manifest.isNewerThan(2))
    }

    @Test
    fun sha256VerificationMatchesBytes() {
        val bytes = "hello".toByteArray()

        assertTrue(UpdateVerifier.matchesSha256(bytes, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        assertFalse(UpdateVerifier.matchesSha256(bytes, "0000000000000000000000000000000000000000000000000000000000000000"))
    }
}
