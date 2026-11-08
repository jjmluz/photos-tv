package com.jjmluz.photostv.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TvTest {

    // The key the TV app derives from "K7QM-2XPA-9RTD" (app/src/test/…/PairingCryptoTest.kt
    // checks the same vector), so the two apps stay in step.
    private val tvKey = SecretKeySpec(
        "c2d20e840ed1d02b2ff7db3512374ab6a89d6b411d121005b4e960525bd76621".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        "AES",
    )

    private fun open(sealed: String): String {
        val envelope = JSONObject(sealed)
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, tvKey, GCMParameterSpec(128, b64.decode(envelope.getString("iv"))))
        return String(cipher.doFinal(b64.decode(envelope.getString("data"))))
    }

    @Test
    fun `seals with the key the TV derives from the code`() {
        val plain = """{"email":"a@b.c"}"""
        assertEquals(plain, open(Tv("192.168.1.5:8765", "K7QM2XPA9RTD").seal(plain)))
    }

    @Test
    fun `code is normalised before deriving the key`() {
        assertEquals("x", open(Tv("h", "k7qm-2xpa-9rtd").seal("x")))
    }

    @Test
    fun `each envelope gets a fresh IV`() {
        val tv = Tv("h", "K7QM2XPA9RTD")
        assertNotEquals(JSONObject(tv.seal("x")).getString("iv"), JSONObject(tv.seal("x")).getString("iv"))
    }
}
