package com.jjmluz.photostv.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedTest {

    /** An item at [time]; keys are their times so overlaps are easy to write. */
    private data class Item(val time: Long) {
        val key get() = time.toString()
    }

    /** A newest-first source of [items] paged [pageSize] at a time; page ids are start indexes. */
    private class Source(val items: List<Item>, val pageSize: Int = 3) {
        var fetches = 0
        fun page(from: Int): Page<Item> {
            fetches++
            val end = minOf(from + pageSize, items.size)
            return Page(items.subList(from, end), end.takeIf { it < items.size }?.toString())
        }
        fun fetch(pageId: String?) = page(pageId?.toInt() ?: 0)
        fun seek(time: Long) = page(items.indexOfFirst { it.time <= time }.takeIf { it >= 0 } ?: items.size)
    }

    private fun TestScope.feed(source: Source, seekable: Boolean = false) = Feed(
        scope = this,
        keyOf = Item::key,
        fetch = { source.fetch(it) },
        seek = if (seekable) { t -> source.seek(t) } else null,
        timeOf = if (seekable) Item::time else null,
    )

    private val DAY = 24L * 60 * 60 * 1000
    // Newest first, a day apart, ending well in the past.
    private val items = (10 downTo 1).map { Item(it * DAY) }

    @Test
    fun `loads pages until the end`() = runTest {
        val source = Source(items)
        val feed = feed(source)
        repeat(4) { feed.loadMore(); advanceUntilIdle() }
        assertEquals(items, feed.state.value.items)
        assertTrue(feed.state.value.endReached)
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(4, source.fetches)
    }

    @Test
    fun `drops items repeated across pages`() = runTest {
        val pages = mapOf(
            null to Page(listOf(Item(3), Item(2)), "b"),
            "b" to Page(listOf(Item(2), Item(1)), null),
        )
        val feed = Feed<Item>(this, Item::key, { pages.getValue(it) })
        feed.loadMore(); advanceUntilIdle()
        feed.loadMore(); advanceUntilIdle()
        assertEquals(listOf(Item(3), Item(2), Item(1)), feed.state.value.items)
    }

    @Test
    fun `keeps the error and recovers on the next load`() = runTest {
        var fail = true
        val feed = Feed<Item>(this, Item::key, {
            if (fail) throw IOException("offline") else Page(listOf(Item(1)), null)
        })
        feed.loadMore(); advanceUntilIdle()
        assertEquals("offline", feed.state.value.error?.message)
        assertFalse(feed.state.value.loading)
        fail = false
        feed.loadMore(); advanceUntilIdle()
        assertNull(feed.state.value.error)
        assertEquals(listOf(Item(1)), feed.state.value.items)
    }

    @Test
    fun `refresh starts over`() = runTest {
        val source = Source(items)
        val feed = feed(source)
        feed.loadMore(); advanceUntilIdle()
        feed.loadMore(); advanceUntilIdle()
        feed.refresh(); advanceUntilIdle()
        assertEquals(items.take(3), feed.state.value.items)
    }

    @Test
    fun `jumps to a moment and pages older from there`() = runTest {
        val feed = feed(Source(items), seekable = true)
        var loaded: List<Item>? = null
        feed.jumpTo(6 * DAY) { loaded = it }
        advanceUntilIdle()
        assertEquals(items.subList(4, 7), feed.state.value.items)
        assertEquals(feed.state.value.items, loaded)
        assertFalse(feed.state.value.atTop)
        feed.loadMore(); advanceUntilIdle()
        assertEquals(items.subList(4, 10), feed.state.value.items)
    }

    @Test
    fun `loads newer items above the window until it reaches the top`() = runTest {
        val feed = feed(Source(items, pageSize = 100), seekable = true)
        feed.jumpTo(5 * DAY)
        advanceUntilIdle()
        assertEquals(items.subList(5, 10), feed.state.value.items)
        feed.loadNewer(); advanceUntilIdle()
        assertEquals(items, feed.state.value.items)
        assertFalse(feed.state.value.atTop)
        // Nothing newer is left, so the next try finds the top.
        feed.loadNewer(); advanceUntilIdle()
        assertTrue(feed.state.value.atTop)
        assertEquals(items, feed.state.value.items)
    }

    @Test
    fun `loads newer narrows the gap so nothing in between is skipped`() = runTest {
        // Pages of 2: a seek far above the top only reaches the newest two, so the feed must
        // narrow its window until a page reaches back to the window's top.
        val feed = feed(Source(items, pageSize = 2), seekable = true)
        feed.jumpTo(5 * DAY)
        advanceUntilIdle()
        assertEquals(items.subList(5, 7), feed.state.value.items)
        repeat(5) { feed.loadNewer(); advanceUntilIdle() }
        assertEquals(items.subList(0, 7), feed.state.value.items)
    }
}
