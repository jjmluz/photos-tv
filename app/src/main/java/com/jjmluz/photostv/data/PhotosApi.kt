package com.jjmluz.photostv.data

import android.util.Log
import android.webkit.CookieManager
import com.jjmluz.photostv.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlin.random.Random

/**
 * Client for the private `batchexecute` RPC endpoint that photos.google.com itself uses.
 *
 * Google removed library-wide read access from the public Library API in March 2025, so
 * this is the only way to browse the full library. It is undocumented and may change;
 * RPC ids and response layouts follow https://github.com/xob0t/Google-Photos-Toolkit.
 */
open class PhotosApi(
    private val http: OkHttpClient,
    /** Makes a new web session when the current one has ended; true if it did. */
    private val renewSession: suspend () -> Boolean = { false },
) {

    /** Per-session tokens embedded in the photos.google.com page (`WIZ_global_data`). */
    private data class PageTokens(
        val at: String,
        val sid: String,
        val buildLabel: String,
        val path: String,
        val account: String?,
    )

    private val mutex = Mutex()
    @Volatile private var tokens: PageTokens? = null

    /** Verifies the session and returns the signed-in account email (if exposed). */
    open suspend fun connect(): String? = try {
        tokens(refresh = true).account
    } catch (e: NotSignedInException) {
        if (!renewSession()) throw e
        tokens(refresh = true).account
    }

    open fun reset() {
        tokens = null
    }

    open suspend fun timeline(pageId: String?): Page<MediaItem> = timelinePage(pageId, null, 300)

    /** Library items taken at or before [timestamp] (UTC epoch millis), newest first. */
    open suspend fun timelineAt(timestamp: Long, pageSize: Int = 300): Page<MediaItem> {
        // A date lookup can answer with an empty page plus a token; the items follow from there.
        var page = timelinePage(null, timestamp, pageSize)
        repeat(10) {
            val next = page.nextPageId
            if (page.items.isNotEmpty() || next == null) return page
            page = timelinePage(next, timestamp, pageSize)
        }
        return page
    }

    private suspend fun timelinePage(pageId: String?, timestamp: Long?, pageSize: Int): Page<MediaItem> {
        // [pageId, timestamp, pageSize, null, 1, source]; source 1 = library without archive.
        val data = rpc("lcxiM", JSONArray(listOf(pageId, timestamp, pageSize, null, 1, 1)))
        return Page(
            items = data.at(0).list().mapNotNull(::parseMediaItem),
            nextPageId = data.at(1).str(),
        )
    }

    open suspend fun albums(pageId: String?): Page<Album> {
        val data = rpc("Z5xsfc", JSONArray(listOf(pageId, null, null, null, 1, null, null, 100, JSONArray(listOf(2)), 5)))
        return Page(
            items = data.at(0).list().mapNotNull(::parseAlbum),
            nextPageId = data.at(1).str(),
        )
    }

    open suspend fun albumItems(album: Album, pageId: String?): Page<MediaItem> {
        val data = rpc("snAcKc", JSONArray(listOf(album.key, pageId, null, album.authKey)))
        return Page(
            items = data.at(1).list().mapNotNull(::parseMediaItem),
            nextPageId = data.at(2).str(),
        )
    }

    /** The people (and pets) Google Photos recognised, most prominent first. */
    open suspend fun people(pageId: String?): Page<Person> {
        val data = rpc("yQelMe", JSONArray(listOf(pageId, null, null, JSONArray(listOf(JSONArray(listOf(1, null, 1)))))))
        // [nextPageId, [[1, [person, …]]]]
        val group = data.at(1).list().firstOrNull { it.at(0).long() == 1L }
        val people = group.at(1).list().mapNotNull(::parsePerson)
        Log.i("PhotosTV", "People: ${people.size} on this page, more=${data.at(0).str() != null}")
        return Page(items = people, nextPageId = data.at(0).str()?.takeIf { people.isNotEmpty() })
    }

    /** Photos of [person], newest first: the web app's search with a person filter (type 1). */
    open suspend fun personItems(person: Person, pageId: String?): Page<MediaItem> {
        val filter = JSONArray(listOf(JSONArray(listOf(1, person.key, 0, 0))))
        val data = rpc("EzkLib", JSONArray(listOf("", filter, pageId)))
        return Page(
            items = data.at(0).list().mapNotNull(::parseMediaItem),
            nextPageId = data.at(1).str(),
        )
    }

    /** The places Google Photos names for [key], most specific first (e.g. "Casa, Salreu"), or null. */
    open suspend fun location(key: String): String? {
        // The item's details panel; [0][13] is its location: [coordinates, ?, [[?, [[name, …]], placeId], …]].
        val data = rpc("fDcn4b", JSONArray(listOf(key, 1, null, null, 1)))
        return data.at(0).at(13).at(2).list()
            .mapNotNull { it.at(1).at(0).at(0).str()?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }
    }

    private suspend fun tokens(refresh: Boolean = false): PageTokens = mutex.withLock {
        tokens?.takeUnless { refresh } ?: withContext(Dispatchers.IO) { fetchTokens() }.also { tokens = it }
    }

    /** For the photos.google.com page: logs each hop (sign-in redirects included) with its time. */
    private val pageClient by lazy {
        http.newBuilder()
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                val via = chain.connection()?.let { "${it.socket().inetAddress.hostAddress} ${it.protocol()}" }
                val start = System.nanoTime()
                fun ms() = (System.nanoTime() - start) / 1_000_000
                try {
                    chain.proceed(chain.request()).also {
                        Log.i("PhotosTV", "Photos page hop: HTTP ${it.code} from ${url.host}${url.encodedPath} in ${ms()} ms via $via")
                    }
                } catch (e: IOException) {
                    Log.w("PhotosTV", "Photos page hop: ${e.message} at ${url.host}${url.encodedPath} after ${ms()} ms via $via")
                    throw e
                }
            }
            .build()
    }

    private fun fetchTokens(): PageTokens {
        val request = Request.Builder().url("https://photos.google.com/").build()
        val cookieHeader = CookieManager.getInstance().getCookie("https://photos.google.com/").orEmpty()
        Log.i("PhotosTV", "Photos page: sending ${cookieHeader.count { it == '=' }} cookies, ${cookieHeader.length} bytes")
        pageClient.newCall(request).execute().use { response ->
            // Signed-out visitors are redirected to the marketing page on www.google.com.
            val finalUrl = response.request.url
            Log.i("PhotosTV", "Photos page: HTTP ${response.code} at ${finalUrl.host}${finalUrl.encodedPath}")
            if (finalUrl.host != "photos.google.com" || finalUrl.encodedPath.startsWith("/about")) {
                throw NotSignedInException()
            }
            if (!response.isSuccessful) throw IOException("photos.google.com answered HTTP ${response.code}")
            val html = response.body!!.string()
            fun field(name: String): String? =
                Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)?.groupValues?.get(1)
                    ?.let { JSONArray("[\"$it\"]").getString(0) }
            return PageTokens(
                at = field("SNlM0e") ?: throw NotSignedInException(),
                sid = field("FdrFJe") ?: throw IOException("The page has no f.sid"),
                buildLabel = field("cfb2h") ?: throw IOException("The page has no app version"),
                path = field("eptZe") ?: "/_/PhotosUi/",
                account = field("oPEP7c"),
            )
        }
    }

    private suspend fun rpc(rpcId: String, args: JSONArray): Any? {
        var attempt = 0
        var renewed = false
        while (true) {
            try {
                return withContext(Dispatchers.IO) { execute(rpcId, args, tokens()) }
            } catch (e: NotSignedInException) {
                if (renewed || !renewSession()) throw e
                renewed = true
                tokens = null
            } catch (e: RateLimitedException) {
                // Google refuses for a while when asked too much: wait it out, and don't add a page fetch to it.
                if (++attempt >= 3) throw e
                delay(5_000L * attempt)
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
                // Tokens can go stale while the app sits in the background; refetch them.
                tokens = null
                delay(1000L * attempt)
            }
        }
    }

    private fun execute(rpcId: String, args: JSONArray, t: PageTokens): Any? {
        val url = "https://photos.google.com${t.path}data/batchexecute".toHttpUrl().newBuilder()
            .addQueryParameter("rpcids", rpcId)
            .addQueryParameter("source-path", "/")
            .addQueryParameter("f.sid", t.sid)
            .addQueryParameter("bl", t.buildLabel)
            // Place names come back in this language.
            .addQueryParameter("hl", AppLocale.toLanguageTag())
            .addQueryParameter("pageId", "none")
            .addQueryParameter("_reqid", Random.nextInt(100_000, 999_999).toString())
            .addQueryParameter("rt", "c")
            .build()
        val envelope = JSONArray(listOf(JSONArray(listOf(JSONArray(listOf(rpcId, args.toString(), null, "generic"))))))
        val body = FormBody.Builder()
            .add("f.req", envelope.toString())
            .add("at", t.at)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("X-Same-Domain", "1")
            .header("Origin", "https://photos.google.com")
            .header("Referer", "https://photos.google.com/")
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw NotSignedInException()
            if (!response.isSuccessful) throw IOException("$rpcId failed with HTTP ${response.code}")
            val text = response.body!!.string()
            val line = text.lineSequence().firstOrNull { "wrb.fr" in it }
                ?: throw IOException("$rpcId: unexpected reply")
            // An empty payload carries Google's error code instead (e.g. [3] for a bad argument): keep it for the log.
            val reply = JSONArray(line).at(0)
            val payload = reply.at(2).str() ?: run {
                val code = reply.at(5).at(0).long()
                if (code == RESOURCE_EXHAUSTED) throw RateLimitedException(rpcId)
                throw IOException("$rpcId: empty reply: ${line.take(300)}")
            }
            return JSONTokener(payload).nextValue()
        }
    }

    private fun parseMediaItem(raw: Any?): MediaItem? {
        val key = raw.at(0).str() ?: return null
        val image = raw.at(1)
        val ext = raw.at(-1) as? JSONObject
        return MediaItem(
            key = key,
            baseUrl = image.at(0).str() ?: return null,
            width = image.at(1).long()?.toInt() ?: 0,
            height = image.at(2).long()?.toInt() ?: 0,
            timestamp = raw.at(2).long() ?: 0,
            timezoneOffsetMs = raw.at(4).long() ?: 0,
            durationMs = ext.key("76647426").at(0).long(),
        )
    }

    private fun parsePerson(raw: Any?): Person? {
        // [[key, [ownerId, clusterId]], [faceUrl, null, timestamp, itemCount, ?, name?], …]
        val info = raw.at(1)
        return Person(
            key = raw.at(0).at(0).str() ?: return null,
            name = info.at(5).str()?.takeIf { it.isNotBlank() },
            faceUrl = info.at(0).str(),
            itemCount = info.at(3).long()?.toInt() ?: 0,
        )
    }

    private fun parseAlbum(raw: Any?): Album? {
        val meta = (raw.at(-1) as? JSONObject).key("72930366")
        return Album(
            key = raw.at(0).str() ?: return null,
            title = meta.at(1).str().orEmpty(),
            coverUrl = raw.at(1).at(0).str(),
            itemCount = meta.at(3).long()?.toInt() ?: 0,
            authKey = meta.at(5).str(),
        )
    }
}

// Null-safe navigation through the nested, positional JSON arrays returned by batchexecute.

private fun Any?.at(index: Int): Any? {
    val array = this as? JSONArray ?: return null
    val i = if (index < 0) array.length() + index else index
    return array.opt(i).takeUnless { it == JSONObject.NULL }
}

private fun Any?.key(name: String): Any? =
    (this as? JSONObject)?.opt(name).takeUnless { it == JSONObject.NULL }

private fun Any?.list(): List<Any?> {
    val array = this as? JSONArray ?: return emptyList()
    return List(array.length()) { array.at(it) }
}

private fun Any?.str(): String? = this as? String

private fun Any?.long(): Long? = (this as? Number)?.toLong() ?: (this as? String)?.toLongOrNull()

/** gRPC's RESOURCE_EXHAUSTED, which batchexecute answers with when asked too often in a short time. */
private const val RESOURCE_EXHAUSTED = 8L

/** Google Photos refused a request for now: too many in a short time (seen with a person's photos, page after page). */
class RateLimitedException(rpcId: String) : IOException("$rpcId: rate limited by Google Photos")
