package com.jjmluz.photostv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.jjmluz.photostv.AppLocale
import java.time.YearMonth
import kotlinx.coroutines.launch
import java.time.format.TextStyle

/** Width of the panel while closed: a strip of years at the right edge of the grid. */
val TimelineRailWidth = 56.dp
private val OpenWidth = 400.dp
private val YearColumnWidth = 120.dp

private sealed interface PanelRow {
    val key: String

    data class Year(val year: Int) : PanelRow {
        override val key get() = "y$year"
    }
    data class Month(val month: YearMonth) : PanelRow {
        override val key get() = "m$month"
    }
}

/**
 * Years and months of the library at the right edge of the timeline. Moving right into it opens
 * it on the month on screen; up/down move between months and OK picks one. Moving right again
 * reaches a column of years for bigger jumps: up/down move between years (the months follow),
 * OK jumps to that year's newest month, left returns to its months. Left from the months, or
 * Back from anywhere, closes the panel.
 */
@Composable
fun TimelinePanel(
    months: List<YearMonth>,
    current: YearMonth?,
    onPick: (YearMonth) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) OpenWidth else TimelineRailWidth, label = "timelineWidth")
    val rows = remember(months) {
        months.groupBy { it.year }.flatMap { (year, ms) -> listOf(PanelRow.Year(year)) + ms.map { PanelRow.Month(it) } }
    }
    val years = remember(months) { months.map { it.year }.distinct() }
    val listState = rememberLazyListState()
    val yearListState = rememberLazyListState()
    val requesters = remember(months) { months.associateWith { FocusRequester() } }
    val yearRequesters = remember(years) { years.associateWith { FocusRequester() } }
    val anchor = current?.takeIf { it in requesters } ?: months.firstOrNull()
    val scope = rememberCoroutineScope()

    // Where focus is inside the panel: a month, or a year in the years column.
    var focusedMonth by remember { mutableStateOf<YearMonth?>(null) }
    var focusedYear by remember { mutableStateOf<Int?>(null) }
    val inYears = focusedYear != null

    fun monthIndex(month: YearMonth) = rows.indexOfFirst { it is PanelRow.Month && it.month == month }
    fun yearHeaderIndex(year: Int) = rows.indexOfFirst { it is PanelRow.Year && it.year == year }

    // Keep the strip lined up with what the grid shows, and land on that month when opened.
    LaunchedEffect(anchor, open) {
        val index = anchor?.let(::monthIndex) ?: -1
        if (index < 0) return@LaunchedEffect
        if (open) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem((index - 4).coerceAtLeast(0))
            requesters[anchor]?.requestFocusSafely()
        } else {
            listState.scrollToItem((index - 4).coerceAtLeast(0))
        }
    }

    // While moving through years, scroll the months to that year so it's clear where OK lands.
    LaunchedEffect(focusedYear) {
        val year = focusedYear ?: return@LaunchedEffect
        val header = yearHeaderIndex(year)
        if (header >= 0) listState.animateScrollToItem(header)
    }

    fun focusYears() {
        val year = (focusedMonth ?: anchor)?.year ?: return
        scope.launch {
            val index = years.indexOf(year)
            if (yearListState.layoutInfo.visibleItemsInfo.none { it.index == index }) yearListState.scrollToItem((index - 3).coerceAtLeast(0))
            yearRequesters[year]?.requestFocusSafely()
        }
    }

    fun focusMonths(year: Int) {
        // Back to the month we came from if it's in this year, else the year's newest month.
        val target = focusedMonth?.takeIf { it.year == year } ?: months.firstOrNull { it.year == year } ?: return
        scope.launch {
            listState.scrollToItem((monthIndex(target) - 4).coerceAtLeast(0))
            requesters[target]?.requestFocusSafely()
        }
    }

    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (open) Brush.horizontalGradient(0f to Color.Transparent, 0.15f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f), 1f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
            )
            .onFocusChanged {
                open = it.hasFocus
                if (!it.hasFocus) focusedYear = null
            }
            .onPreviewKeyEvent { event ->
                if (!open) return@onPreviewKeyEvent false
                val down = event.type == KeyEventType.KeyDown
                when (event.key) {
                    Key.Back -> { if (!down) onClose(); true }
                    Key.DirectionLeft -> {
                        if (down) {
                            val year = focusedYear
                            if (year != null) focusMonths(year)
                        } else if (!inYears) {
                            onClose()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (down && !inYears) focusYears()
                        true
                    }
                    else -> false
                }
            }
            .focusGroup(),
    ) {
        Row(Modifier.fillMaxHeight()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().weight(1f),
                contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp, start = if (open) 72.dp else 0.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows.size, key = { rows[it].key }) { i ->
                    when (val row = rows[i]) {
                        is PanelRow.Year -> Text(
                            row.year.toString(),
                            style = if (open) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
                            color = if (open) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = if (open) 16.dp else 10.dp, bottom = 4.dp),
                        )
                        is PanelRow.Month -> MonthRow(
                            month = row.month,
                            open = open,
                            isCurrent = row.month == current,
                            onClick = { onPick(row.month) },
                            modifier = Modifier
                                .focusRequester(requesters.getValue(row.month))
                                .onFocusChanged { f -> if (f.hasFocus) { focusedMonth = row.month; focusedYear = null } },
                        )
                    }
                }
            }
            if (open) {
                LazyColumn(
                    state = yearListState,
                    modifier = Modifier.fillMaxHeight().width(YearColumnWidth),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(years.size, key = { years[it] }) { i ->
                        val year = years[i]
                        YearRow(
                            year = year,
                            // Highlight the year of the month being chosen while in the months column.
                            selected = !inYears && (focusedMonth ?: anchor)?.year == year,
                            onClick = { months.firstOrNull { it.year == year }?.let(onPick) },
                            modifier = Modifier
                                .focusRequester(yearRequesters.getValue(year))
                                .onFocusChanged { f -> if (f.hasFocus) focusedYear = year },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YearRow(year: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(year.toString(), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun MonthRow(month: YearMonth, open: Boolean, isCurrent: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, AppLocale).capitalized()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (open) 40.dp else 8.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    ) {
        if (open) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            // Closed: a tick per month, the one on screen highlighted.
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(width = if (isCurrent) 20.dp else 8.dp, height = 3.dp)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            CircleShape,
                        ),
                )
            }
        }
    }
}
