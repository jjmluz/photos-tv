package com.jjmluz.photostv.data

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JamendoTest {

    private val requests = mutableListOf<okhttp3.HttpUrl>()

    /** A client whose every call answers with the next of [replies] (the last one repeats). */
    private fun client(vararg replies: Pair<Int, String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request().url
            val (code, body) = replies[minOf(requests.size - 1, replies.lastIndex)]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun track(name: String, vocals: String = "instrumental", audio: String = "https://a/$name.mp3") =
        JSONObject().put("name", name).put("artist_name", "Artist $name").put("audio", audio)
            .put("musicinfo", JSONObject().put("vocalinstrumental", vocals))

    private fun ok(vararg tracks: JSONObject) = 200 to JSONObject()
        .put("headers", JSONObject().put("status", "success"))
        .put("results", JSONArray(tracks.toList()))
        .toString()

    @Test
    fun `is available only with a client id`() {
        assertFalse(Jamendo(OkHttpClient(), "").available)
        assertTrue(Jamendo(OkHttpClient(), "id").available)
    }

    @Test
    fun `keeps instrumental tracks that have audio`() = runTest {
        val jamendo = Jamendo(client(ok(track("a"), track("b", vocals = "vocal"), track("c", audio = ""))), "id")
        assertEquals(listOf(Track("a", "Artist a", "https://a/a.mp3")), jamendo.tracks("happy"))
        val url = requests.single()
        assertEquals("id", url.queryParameter("client_id"))
        assertEquals("happy", url.queryParameter("tags"))
    }

    @Test
    fun `asks again after an empty answer`() = runTest {
        val jamendo = Jamendo(client(ok(), ok(), ok(track("a"))), "id")
        assertEquals(listOf("a"), jamendo.tracks("calm").map { it.title })
        assertEquals(3, requests.size)
    }

    @Test
    fun `gives up after four empty answers`() = runTest {
        val jamendo = Jamendo(client(ok()), "id")
        assertEquals(emptyList<Track>(), jamendo.tracks("calm"))
        assertEquals(4, requests.size)
    }

    @Test
    fun `caches tracks per tag`() = runTest {
        val jamendo = Jamendo(client(ok(track("a"), track("b"))), "id")
        jamendo.tracks("calm")
        assertEquals(setOf("a", "b"), jamendo.tracks("calm").map { it.title }.toSet())
        assertEquals(1, requests.size)
        jamendo.tracks("happy")
        assertEquals(2, requests.size)
    }

    @Test
    fun `reports API and HTTP errors`() = runTest {
        val apiError = 200 to """{"headers":{"status":"failed","error_message":"bad client"}}"""
        val e = runCatching { Jamendo(client(apiError), "id").tracks("calm") }.exceptionOrNull()
        assertTrue(e is IOException && "bad client" in e.message!!)
        assertTrue(runCatching { Jamendo(client(500 to ""), "id").tracks("calm") }.exceptionOrNull() is IOException)
    }
}
