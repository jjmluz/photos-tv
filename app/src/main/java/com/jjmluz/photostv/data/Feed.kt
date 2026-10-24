package com.jjmluz.photostv.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val PREPEND_CHUNK = 60

/**
 * An incrementally loaded list backed by a page-token API.
 *
 * With [seek] and [timeOf] (a newest-first, time-ordered source such as the timeline) the feed
 * can also [jumpTo] a moment and then grow upwards with [loadNewer], so it holds a contiguous
 * window of the source rather than always starting at the newest item.
 */
class Feed<T>(
    private val scope: CoroutineScope,
    private val keyOf: (T) -> String,
    private val fetch: suspend (pageId: String?) -> Page<T>,
    private val seek: (suspend (timestamp: Long) -> Page<T>)? = null,
    private val timeOf: ((T) -> Long)? = null,
) {
    data class State<T>(
        val items: List<T> = emptyList(),
        val loading: Boolean = false,
        val error: Throwable? = null,
        val endReached: Boolean = false,
        /** Whether the window starts at the newest item (nothing newer left to load). */
        val atTop: Boolean = true,
    )

    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state.asStateFlow()

    private var nextPageId: String? = null
    private val seen = HashSet<String>()
    private var job: Job? = null
    private var newerJob: Job? = null

    fun loadMore() {
        if (job?.isActive == true || _state.value.endReached) return
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val page = fetch(nextPageId)
                nextPageId = page.nextPageId
                // Pages can overlap; lazy lists require unique keys.
                val fresh = page.items.filter { seen.add(keyOf(it)) }
                _state.update {
                    it.copy(items = it.items + fresh, loading = false, endReached = page.nextPageId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PhotosTV", "Feed page failed", e)
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun refresh() {
        job?.cancel()
        newerJob?.cancel()
        nextPageId = null
        seen.clear()
        _state.value = State()
        loadMore()
    }

    /** Restarts the window at [timestamp]; [onLoaded] gets the first page once it's shown. */
    fun jumpTo(timestamp: Long, onLoaded: (List<T>) -> Unit = {}) {
        val seek = seek ?: return
        job?.cancel()
        newerJob?.cancel()
        seen.clear()
        _state.value = State(loading = true, atTop = false)
        job = scope.launch {
            try {
                val page = seek(timestamp)
                nextPageId = page.nextPageId
                val items = page.items.filter { seen.add(keyOf(it)) }
                _state.value = State(items = items, endReached = page.nextPageId == null, atTop = false)
                onLoaded(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PhotosTV", "Feed jump failed", e)
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    /**
     * Prepends items newer than the window's top. The source only pages towards older items, so
     * this seeks to a point above the top and narrows the gap until the page reaches back to it,
     * which guarantees nothing in between is skipped.
     */
    fun loadNewer() {
        val seek = seek ?: return
        val timeOf = timeOf ?: return
        val current = _state.value
        if (current.atTop || newerJob?.isActive == true || job?.isActive == true) return
        val top = current.items.firstOrNull() ?: return
        val topTime = timeOf(top)
        newerJob = scope.launch {
            try {
                var window = 30 * DAY_MS
                repeat(40) {
                    val target = topTime + window
                    val page = seek(target)
                    val reachesTop = page.items.isEmpty() || timeOf(page.items.last()) <= topTime
                    if (!reachesTop) {
                        window /= 2
                        return@repeat
                    }
                    val newer = page.items.filter { timeOf(it) >= topTime && keyOf(it) !in seen }
                    if (newer.isNotEmpty()) {
                        newer.forEach { seen.add(keyOf(it)) }
                        // Lazy grids keep the visible item in place across an insert above it only
                        // when it moves by less than ~100 positions, so add a frame's worth at a time.
                        newer.asReversed().chunked(PREPEND_CHUNK).forEach { chunk ->
                            _state.update { it.copy(items = chunk.asReversed() + it.items) }
                            delay(32)
                        }
                        return@launch
                    }
                    if (target > System.currentTimeMillis() + DAY_MS) {
                        _state.update { it.copy(atTop = true) }
                        return@launch
                    }
                    window *= 4
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PhotosTV", "Feed newer page failed", e)
            }
        }
    }
}
