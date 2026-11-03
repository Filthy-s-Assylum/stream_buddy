package com.streambuddy.app.core.security

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AesGcmCipherTest {
    private val cipher = AesGcmCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test
    fun `round trips and uses a fresh IV per value`() {
        val first = cipher.encrypt("api-key-123")
        val second = cipher.encrypt("api-key-123")
        assertNotEquals(first, second)
        assertEquals("api-key-123", cipher.decrypt(first))
        assertEquals("api-key-123", cipher.decrypt(second))
    }
}
