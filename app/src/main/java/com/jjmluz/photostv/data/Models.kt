package com.jjmluz.photostv.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

data class MediaItem(
    val key: String,
    /** lh3.googleusercontent.com base URL; append size parameters before loading. */
    val baseUrl: String,
    val width: Int,
    val height: Int,
    /** Capture time in UTC epoch millis. */
    val timestamp: Long,
    val timezoneOffsetMs: Long,
    /** Non-null for videos. */
    val durationMs: Long?,
) {
    val isVideo get() = durationMs != null

    /** Wall-clock time where the photo was taken. */
    val localDateTime: LocalDateTime
        get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp + timezoneOffsetMs), ZoneOffset.UTC)

    // "-rw" asks for WebP, which is compact and decodes natively.
    /**
     * The whole frame with its shorter side at [px], so the viewer can stretch it as a placeholder
     * without changing shape (tiles crop it themselves). A square crop when the size is unknown.
     */
    fun thumbnailUrl(px: Int): String {
        if (width <= 0 || height <= 0) return "$baseUrl=w$px-h$px-c-k-no-rw"
        val long = (px.toLong() * maxOf(width, height) / minOf(width, height)).toInt().coerceAtMost(px * 3)
        return if (width >= height) "$baseUrl=w$long-h$px-k-no-rw" else "$baseUrl=w$px-h$long-k-no-rw"
    }
    fun fullUrl(maxWidth: Int, maxHeight: Int) = "$baseUrl=w$maxWidth-h$maxHeight-k-no-rw"
    /**
     * Playback candidates, best first. Google's adaptive HLS stream starts fast and steps up to
     * its best rendition (1080p: Google Photos doesn't stream above that); the progressive
     * 1080p/720p files are fallbacks. Its DASH manifest has the same renditions but its segments
     * are refused (403). The original upload comes last: its server ignores byte ranges, so a
     * large file with its index at the end can't start until it is mostly downloaded.
     */
    val videoSources: List<VideoSource>
        get() = listOf(
            VideoSource("$baseUrl=mm,hls", "application/x-mpegURL"),
            VideoSource("$baseUrl=m37"),
            VideoSource("$baseUrl=m22"),
            VideoSource("$baseUrl=dv"),
        )
}

data class VideoSource(val url: String, val mimeType: String? = null)

data class Album(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val itemCount: Int,
    /** Present for shared albums; required to page through their items. */
    val authKey: String?,
) {
    fun coverUrl(px: Int) = coverUrl?.let { "$it=w$px-h$px-c-k-no-rw" }
}

data class Person(
    val key: String,
    /** Null until the user names the face group in Google Photos. */
    val name: String?,
    val faceUrl: String?,
    val itemCount: Int,
) {
    fun faceUrl(px: Int) = faceUrl?.let { "$it=w$px-h$px-c-k-no-rw" }
}

data class Page<T>(val items: List<T>, val nextPageId: String?)

/** The signed-in Google account, for the avatar in the top bar. */
data class Profile(val name: String?, val email: String?, val pictureUrl: String?) {
    /** Google account pictures take a size suffix like `=s96-c`. */
    fun pictureUrl(px: Int) = pictureUrl?.let { "${it.substringBefore('=')}=s$px-c" }
}

class NotSignedInException : Exception("Not signed in to Google Photos")
