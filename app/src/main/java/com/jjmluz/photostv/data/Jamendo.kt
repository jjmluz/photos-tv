package com.jjmluz.photostv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** A Jamendo track. Most are CC BY, so the artist is credited on screen while it plays. */
data class Track(val title: String, val artist: String, val audioUrl: String)

/**
 * Jamendo's catalogue of Creative Commons music (developer.jamendo.com/v3.0), streamed rather
 * than bundled. Free for non-commercial apps, with a client id from devportal.jamendo.com.
 */
class Jamendo(private val http: OkHttpClient, private val clientId: String) {

    val available get() = clientId.isNotEmpty()

    private val cache = HashMap<String, List<Track>>()

    /** This month's most popular instrumental tracks tagged [tag], in a new random order each time. */
    suspend fun tracks(tag: String): List<Track> {
        val tracks = cache[tag] ?: fetchWithRetries(tag).also { if (it.isNotEmpty()) cache[tag] = it }
        return tracks.shuffled()
    }

    // The API answers the same query with no results now and then (about one time in three),
    // more so with its vocalinstrumental filter, so instrumentals are picked here and empty
    // answers are asked again.
    private suspend fun fetchWithRetries(tag: String): List<Track> {
        repeat(ATTEMPTS - 1) {
            fetch(tag).let { if (it.isNotEmpty()) return it }
            delay(RETRY_DELAY_MS)
        }
        return fetch(tag)
    }

    private suspend fun fetch(tag: String): List<Track> = withContext(Dispatchers.IO) {
        val url = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", "200")
            .addQueryParameter("tags", tag)
            .addQueryParameter("include", "musicinfo")
            .addQueryParameter("audioformat", "mp32")
            .addQueryParameter("order", "popularity_month")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Jamendo: HTTP ${response.code}")
            val json = JSONObject(response.body!!.string())
            val headers = json.getJSONObject("headers")
            if (headers.optString("status") != "success") throw IOException("Jamendo: ${headers.optString("error_message")}")
            val results = json.getJSONArray("results")
            List(results.length()) { results.getJSONObject(it) }.mapNotNull { track ->
                val vocals = track.optJSONObject("musicinfo")?.optString("vocalinstrumental")
                if (vocals != "instrumental") return@mapNotNull null
                val audio = track.optString("audio").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Track(track.optString("name"), track.optString("artist_name"), audio)
            }
        }
    }

    private companion object {
        const val ATTEMPTS = 4
        const val RETRY_DELAY_MS = 1_000L
    }
}
