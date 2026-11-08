package com.jjmluz.photostv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingCryptoTest {

    /** Seals [plain] the way the phone app does (phone/…/Tv.kt). */
    private fun phoneSeal(key: SecretKeySpec, plain: String): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(cipher.doFinal(plain.toByteArray())))
            .toString().toByteArray()
    }

    @Test
    fun `key matches the vector the phone app is tested against`() {
        // Same vector as phone/src/test/…/TvTest.kt: PBKDF2-SHA256, salt "photos-tv-cookie-sync", 100k rounds.
        val key = PairingServer.deriveKey("K7QM-2XPA-9RTD")
        assertEquals("c2d20e840ed1d02b2ff7db3512374ab6a89d6b411d121005b4e960525bd76621", key.encoded.toHex())
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun `key ignores dashes and case`() {
        assertEquals(
            PairingServer.deriveKey("K7QM-2XPA-9RTD").encoded.toHex(),
            PairingServer.deriveKey("k7qm2xpa9rtd").encoded.toHex(),
        )
    }

    @Test
    fun `opens what the phone sealed`() {
        val key = PairingServer.deriveKey("K7QM-2XPA-9RTD")
        val payload = PairingServer.unseal(key, phoneSeal(key, """{"sentAt":42,"email":"a@b.c","oauthToken":"tok"}"""))
        assertEquals(42L, payload.getLong("sentAt"))
        assertEquals("a@b.c", payload.getString("email"))
        assertEquals("tok", payload.getString("oauthToken"))
    }

    @Test
    fun `refuses an envelope sealed with another code`() {
        val sealed = phoneSeal(PairingServer.deriveKey("AAAA-BBBB-CCCC"), """{"sentAt":1}""")
        assertThrows(Exception::class.java) { PairingServer.unseal(PairingServer.deriveKey("K7QM-2XPA-9RTD"), sealed) }
    }

    @Test
    fun `refuses a tampered envelope`() {
        val key = PairingServer.deriveKey("K7QM-2XPA-9RTD")
        val envelope = JSONObject(String(phoneSeal(key, """{"sentAt":1}""")))
        val data = Base64.getDecoder().decode(envelope.getString("data"))
        data[0] = (data[0].toInt() xor 1).toByte()
        envelope.put("data", Base64.getEncoder().encodeToString(data))
        assertThrows(Exception::class.java) { PairingServer.unseal(key, envelope.toString().toByteArray()) }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
