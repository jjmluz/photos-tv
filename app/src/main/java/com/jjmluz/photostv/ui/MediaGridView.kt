package com.jjmluz.photostv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.AppLocale
import com.jjmluz.photostv.MediaGrid
import com.jjmluz.photostv.data.MediaItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private sealed interface Entry {
    val key: String

    data class Header(val label: String, override val key: String) : Entry
    data class Tile(val index: Int, val item: MediaItem) : Entry {
        override val key get() = item.key
    }
}

private const val COLUMNS = 6
private val SameYear = DateTimeFormatter.ofPattern("EEE, d 'de' MMMM", AppLocale)
private val OtherYear = DateTimeFormatter.ofPattern("EEE, d 'de' MMMM 'de' yyyy", AppLocale)

private fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Hoje"
    date == today.minusDays(1) -> "Ontem"
    date.year == today.year -> date.format(SameYear).capitalized()
    else -> date.format(OtherYear).capitalized()
}

private fun buildEntries(items: List<MediaItem>, groupByDay: Boolean): List<Entry> {
    if (!groupByDay) return items.mapIndexed { i, item -> Entry.Tile(i, item) }
    val today = LocalDate.now()
    val out = ArrayList<Entry>(items.size + items.size / 8)
    var day: LocalDate? = null
    items.forEachIndexed { i, item ->
        val d = item.localDateTime.toLocalDate()
        if (d != day) {
            day = d
            out += Entry.Header(dayLabel(d, today), "day-$d")
        }
        out += Entry.Tile(i, item)
    }
    return out
}

/**
 * Paged grid of photos and videos. Loads more as the user nears the end and moves focus
 * back to [MediaGrid.restoreKey] when it is set.
 */
@Composable
fun MediaGridView(
    grid: MediaGrid,
    onOpen: (index: Int) -> Unit,
    onSignIn: () -> Unit,
    groupByDay: Boolean,
    modifier: Modifier = Modifier,
    endPadding: Dp = 48.dp,
    title: (@Composable () -> Unit)? = null,
) {
    val state by grid.feed.state.collectAsStateWithLifecycle()
    val entries = remember(state.items, groupByDay) { buildEntries(state.items, groupByDay) }
    val positions = remember(entries) { entries.withIndex().associate { (i, e) -> e.key to i } }
    val offset = if (title != null) 1 else 0

    if (state.items.isEmpty()) {
        // An empty page with more to come (e.g. right after a date jump): keep loading.
        if (!state.loading && state.error == null && !state.endReached) {
            LaunchedEffect(state) { grid.feed.loadMore() }
        }
        when {
            state.error != null -> FeedError(state.error!!, grid.feed::refresh, onSignIn)
            state.endReached -> MessageScreen("Ainda não há nada aqui", "As fotos e os vídeos da sua cópia de segurança aparecem aqui.", icon = Icons.Outlined.PhotoLibrary)
            else -> LoadingScreen()
        }
        return
    }

    // Bring the item to restore on screen; the tile itself takes focus when composed.
    val restoreKey = grid.restoreKey
    LaunchedEffect(restoreKey, positions) {
        val pos = restoreKey?.let(positions::get) ?: return@LaunchedEffect
        val visible = grid.gridState.layoutInfo.visibleItemsInfo
        // The first item scrolls all the way up, so its heading shows too.
        if (restoreKey == state.items.first().key) grid.gridState.scrollToItem(0)
        else if (visible.none { it.key == restoreKey }) grid.gridState.scrollToItem(pos + offset)
    }

    LaunchedEffect(grid, entries.size) {
        snapshotFlow { grid.gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= entries.size - COLUMNS * 6) grid.feed.loadMore() }
    }
    // After jumping to a date, fill in newer items as the user scrolls up.
    LaunchedEffect(grid, entries.size) {
        snapshotFlow { grid.gridState.firstVisibleItemIndex }
            .collect { first -> if (first < COLUMNS * 4) grid.feed.loadNewer() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = grid.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageStartPadding, end = endPadding, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) { title() }
        }
        items(
            count = entries.size,
            key = { entries[it].key },
            span = { if (entries[it] is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { entries[it]::class },
        ) { i ->
            when (val entry = entries[i]) {
                is Entry.Header -> Text(
                    entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 20.dp, bottom = 2.dp),
                )
                is Entry.Tile -> {
                    val focus = remember { FocusRequester() }
                    if (entry.key == restoreKey) {
                        LaunchedEffect(restoreKey) {
                            focus.requestFocusSafely()
                            grid.restoreKey = null
                        }
                    }
                    ImageTile(
                        url = entry.item.thumbnailUrl(THUMB_PX),
                        onClick = { onOpen(entry.index) },
                        modifier = Modifier
                            .aspectRatio(1f)
                            .focusRequester(focus)
                            .onFocusChanged { grid.focusedKey = trackFocus(grid.focusedKey, entry.key, it.hasFocus) },
                        overlay = {
                            entry.item.durationMs?.let {
                                VideoBadge(it, Modifier.align(Alignment.BottomEnd))
                            }
                        },
                    )
                }
            }
        }
        if (state.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                LoadingScreen(Modifier.padding(24.dp))
            }
        }
    }
}
