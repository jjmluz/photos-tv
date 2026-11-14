package com.jjmluz.photostv.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.jjmluz.photostv.app
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Lets the family's TV games use the signed-in account's People: their names and faces, their
 * photos with the time each was taken, a photo's place, and the pictures themselves. The games never see
 * the account's token or cookies: everything goes through this app's own session.
 *
 * Callers need [PERMISSION], which the TV asks the person in front of it about, as for the camera.
 *
 * Methods, through [call]; each answers a Bundle with `json`, or with `error` (`not_signed_in`, or a message):
 * - `people`: `[{key, name, count}]`, the named people only.
 * - `photos` (arg: a person's key, extra `pages`: how many pages of their photos, newest first; 3 by default):
 *   `[{key, time, offset, video}]`, `time` in UTC epoch millis and `offset` the time zone's, in millis.
 * - `place` (arg: a photo's key): `{place}`, its place names, most specific first, or null.
 *
 * Pictures, through [openFile]: `content://com.jjmluz.photostv.games/face/<person key>?px=200` and
 * `/photo/<photo key>?w=1920&h=1080`, JPEG or WebP. Only people and photos a [call] has listed can be opened.
 */
class GameProvider : ContentProvider() {
    /** Image addresses by person or photo key, from the lists handed out; a caller can't name any other URL. */
    private val faces = ConcurrentHashMap<String, String>()
    private val photos = ConcurrentHashMap<String, String>()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check()
        val api = context!!.app.api
        return try {
            val json = runBlocking {
                when (method) {
                    "people" -> {
                        val all = mutableListOf<Person>()
                        var page: String? = null
                        do {
                            val p = api.people(page)
                            all += p.items
                            page = p.nextPageId
                        } while (page != null && all.size < MAX_PEOPLE)
                        JSONArray(all.filter { !it.name.isNullOrBlank() }.map { person ->
                            person.faceUrl?.let { faces[person.key] = it }
                            JSONObject().put("key", person.key).put("name", person.name).put("count", person.itemCount)
                        }).toString()
                    }
                    "photos" -> {
                        val person = Person(requireNotNull(arg) { "No person" }, null, null, 0)
                        val pages = extras?.getInt("pages", 3)?.coerceIn(1, 20) ?: 3
                        val items = mutableListOf<MediaItem>()
                        var page: String? = null
                        repeat(pages) {
                            if (it > 0 && page == null) return@repeat
                            val p = api.personItems(person, page)
                            items += p.items
                            page = p.nextPageId
                        }
                        JSONArray(items.map { item ->
                            photos[item.key] = item.baseUrl
                            JSONObject().put("key", item.key).put("time", item.timestamp)
                                .put("offset", item.timezoneOffsetMs).put("video", item.isVideo)
                        }).toString()
                    }
                    "place" -> JSONObject().put("place", api.location(requireNotNull(arg) { "No photo" }) ?: JSONObject.NULL).toString()
                    else -> throw IllegalArgumentException("Unknown method $method")
                }
            }
            Bundle().apply { putString("json", json) }
        } catch (e: NotSignedInException) {
            Bundle().apply { putString("error", "not_signed_in") }
        } catch (e: Exception) {
            Log.w("PhotosTV", "Games: $method failed", e)
            Bundle().apply { putString("error", e.message ?: e.javaClass.simpleName) }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check()
        val segments = uri.pathSegments
        val key = segments.getOrNull(1) ?: throw FileNotFoundException(uri.toString())
        val url = when (segments.firstOrNull()) {
            "face" -> faces[key]?.let { base ->
                val px = uri.getQueryParameter("px")?.toIntOrNull()?.coerceIn(16, 1024) ?: 256
                "$base=w$px-h$px-c-k-no"
            }
            "photo" -> photos[key]?.let { base ->
                val w = uri.getQueryParameter("w")?.toIntOrNull()?.coerceIn(16, 3840) ?: 1920
                val h = uri.getQueryParameter("h")?.toIntOrNull()?.coerceIn(16, 2160) ?: 1080
                "$base=w$w-h$h-k-no"
            }
            else -> null
        } ?: throw FileNotFoundException("Not listed: $uri")
        // A pipe, so the picture streams to the caller while it downloads.
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        val http = context!!.app.http
        thread(name = "games-image") {
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out -> response.body!!.byteStream().copyTo(out) }
                }
            } catch (e: Exception) {
                Log.w("PhotosTV", "Games: picture failed: ${e.message}")
                runCatching { write.closeWithError(e.message ?: "failed") }
            }
        }
        return read
    }

    /** The permission is the provider's, but [call] isn't covered by it: check every request ourselves. */
    private fun check() {
        if (context!!.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Needs $PERMISSION")
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

    companion object {
        const val PERMISSION = "com.jjmluz.photostv.permission.GAMES"
        private const val MAX_PEOPLE = 500
    }
}
