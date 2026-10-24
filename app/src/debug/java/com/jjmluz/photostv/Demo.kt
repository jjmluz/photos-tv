package com.jjmluz.photostv

import android.os.Build
import com.jjmluz.photostv.data.Album
import com.jjmluz.photostv.data.MediaItem
import com.jjmluz.photostv.data.Page
import com.jjmluz.photostv.data.Person
import com.jjmluz.photostv.data.PhotosApi
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.random.Random

/*
 * Debug builds on an emulator show a made-up library instead of Google Photos, so the UI can be
 * tried without importing a session. Images come from picsum.photos.
 */

private const val DEMO_HOST = "demo.invalid"
private const val PAGE = 60

private val onEmulator = Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic")

fun demoApi(): PhotosApi? = if (onEmulator) DemoPhotosApi() else null

/** Public sample clips of different lengths, standing in for the library's videos. */
private val sampleVideos = listOf(
    "https://media.w3.org/2010/05/sintel/trailer.mp4",
    "https://archive.org/download/ElephantsDream/ed_1024_512kb.mp4",
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4",
)

/**
 * Turns demo URLs ("https://demo.invalid/<seed>=w400-h400-…") into picsum.photos images, and video
 * renditions ("…=m37") into sample clips. The HLS stream is refused so the fallback path runs too.
 */
fun demoImages(): Interceptor? = if (!onEmulator) null else Interceptor { chain ->
    val url = chain.request().url
    if (url.host != DEMO_HOST) return@Interceptor chain.proceed(chain.request())
    val (seed, params) = url.encodedPath.removePrefix("/").split("=", limit = 2) + listOf("")
    if (params == "mm,hls") {
        return@Interceptor Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody())
            .build()
    }
    if (params in setOf("m37", "m22", "dv")) {
        val clip = sampleVideos[seed.filter(Char::isDigit).toInt() % sampleVideos.size]
        return@Interceptor chain.proceed(chain.request().newBuilder().url(clip.toHttpUrl()).build())
    }
    val w = Regex("w(\\d+)").find(params)?.groupValues?.get(1) ?: "400"
    val h = Regex("-h(\\d+)").find(params)?.groupValues?.get(1) ?: "400"
    // Thumbnails are square crops; full images keep a 3:2 landscape frame within the box.
    val (width, height) = if ("-c" in params) w to h else w to (w.toInt() * 2 / 3).coerceAtMost(h.toInt()).toString()
    chain.proceed(chain.request().newBuilder().url("https://picsum.photos/seed/$seed/$width/$height".toHttpUrl()).build())
}

private class DemoPhotosApi : PhotosApi(OkHttpClient()) {
    private val day = 24 * 60 * 60 * 1000L
    private val offset = 60 * 60 * 1000L

    private val items: List<MediaItem> = run {
        val random = Random(7)
        var time = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        List(400) { i ->
            // Bursts of photos a few minutes to hours apart, with gaps of days between outings.
            time -= random.nextLong(10, 180) * 60 * 1000L + if (random.nextInt(10) == 0) random.nextLong(1, 15) * day else 0
            MediaItem(
                key = "demo$i",
                baseUrl = "https://$DEMO_HOST/photo$i",
                width = 3000,
                height = 2000,
                timestamp = time,
                timezoneOffsetMs = offset,
                durationMs = if (i % 11 == 4) random.nextLong(5_000, 180_000) else null,
            )
        }
    }

    private val albumList = listOf(
        "Férias no Algarve", "Natal 2025", "Casamento", "Fim de semana no Porto", "Aniversário",
        "Caminhadas", "Praia", "Jardim", "Viagem a Londres", "Família",
    ).mapIndexed { i, title ->
        Album("album$i", title, "https://$DEMO_HOST/album$i", 20 + i * 13, authKey = null)
    }

    private val peopleList = listOf("Sara", "Miguel", "Lili", "João", null, "Avó", null, "Rita", "Tomás", null)
        .mapIndexed { i, name -> Person("person$i", name, "https://$DEMO_HOST/face$i", 300 - i * 25) }

    private val places = listOf("Casa, Salreu", "Lisboa", "Porto, Ribeira", null, "Praia da Rocha, Portimão", null)

    override suspend fun connect(): String = "demo@example.com"

    override fun reset() {}

    override suspend fun timeline(pageId: String?): Page<MediaItem> = slice(items, pageId)

    override suspend fun timelineAt(timestamp: Long, pageSize: Int): Page<MediaItem> {
        val start = items.indexOfFirst { it.timestamp <= timestamp }
        if (start < 0) return Page(emptyList(), null)
        return slice(items, start.toString(), pageSize)
    }

    override suspend fun albums(pageId: String?): Page<Album> = Page(albumList, null)

    override suspend fun albumItems(album: Album, pageId: String?): Page<MediaItem> =
        slice(items.filterIndexed { i, _ -> i % albumList.size == albumList.indexOf(album) || i % 7 == 0 }, pageId)

    override suspend fun people(pageId: String?): Page<Person> = Page(peopleList, null)

    override suspend fun personItems(person: Person, pageId: String?): Page<MediaItem> =
        slice(items.filterIndexed { i, _ -> i % 3 == peopleList.indexOf(person) % 3 }, pageId)

    override suspend fun location(key: String): String? {
        delay(150)
        return places[key.removePrefix("demo").toInt() % places.size]
    }

    private suspend fun <T> slice(all: List<T>, pageId: String?, size: Int = PAGE): Page<T> {
        delay(300)
        val start = pageId?.toInt() ?: 0
        val end = (start + size).coerceAtMost(all.size)
        return Page(all.subList(start, end), end.takeIf { it < all.size }?.toString())
    }
}
