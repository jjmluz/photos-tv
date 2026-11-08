package com.jjmluz.photostv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ModelsTest {

    private fun item(width: Int = 4000, height: Int = 3000, durationMs: Long? = null) =
        MediaItem("k", "https://lh3/x", width, height, timestamp = 0, timezoneOffsetMs = 0, durationMs = durationMs)

    @Test
    fun `landscape thumbnail keeps the shape with the short side at the size`() {
        assertEquals("https://lh3/x=w400-h300-k-no-rw", item(4000, 3000).thumbnailUrl(300))
    }

    @Test
    fun `portrait thumbnail keeps the shape with the short side at the size`() {
        assertEquals("https://lh3/x=w300-h400-k-no-rw", item(3000, 4000).thumbnailUrl(300))
    }

    @Test
    fun `panorama thumbnail is capped at three times the size`() {
        assertEquals("https://lh3/x=w900-h300-k-no-rw", item(20000, 1000).thumbnailUrl(300))
    }

    @Test
    fun `thumbnail of unknown size is a square crop`() {
        assertEquals("https://lh3/x=w300-h300-c-k-no-rw", item(0, 0).thumbnailUrl(300))
    }

    @Test
    fun `full url fits the given box`() {
        assertEquals("https://lh3/x=w1920-h1080-k-no-rw", item().fullUrl(1920, 1080))
    }

    @Test
    fun `items with a duration are videos, HLS first and the original last`() {
        assertFalse(item().isVideo)
        val video = item(durationMs = 5_000)
        assertTrue(video.isVideo)
        val sources = video.videoSources
        assertEquals(VideoSource("https://lh3/x=mm,hls", "application/x-mpegURL"), sources.first())
        assertEquals("https://lh3/x=dv", sources.last().url)
    }

    @Test
    fun `local time applies the timezone offset`() {
        // 2024-06-01T23:30Z taken at UTC+1 was 00:30 on the 2nd where it was taken.
        val utc = java.time.Instant.parse("2024-06-01T23:30:00Z").toEpochMilli()
        val photo = MediaItem("k", "u", 1, 1, utc, timezoneOffsetMs = 3_600_000, durationMs = null)
        assertEquals(LocalDateTime.of(2024, 6, 2, 0, 30), photo.localDateTime)
    }

    @Test
    fun `album and person images are square crops, absent when there is none`() {
        assertEquals("c=w200-h200-c-k-no-rw", Album("a", "t", "c", 1, null).coverUrl(200))
        assertNull(Album("a", "t", null, 1, null).coverUrl(200))
        assertEquals("f=w96-h96-c-k-no-rw", Person("p", null, "f", 1).faceUrl(96))
        assertNull(Person("p", null, null, 1).faceUrl(96))
    }

    @Test
    fun `profile picture replaces any existing size suffix`() {
        assertEquals("https://lh3/a=s96-c", Profile(null, null, "https://lh3/a=s32-c").pictureUrl(96))
        assertEquals("https://lh3/a=s96-c", Profile(null, null, "https://lh3/a").pictureUrl(96))
        assertNull(Profile(null, null, null).pictureUrl(96))
    }
}
