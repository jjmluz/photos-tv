package com.jjmluz.photostv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text
import com.jjmluz.photostv.MainViewModel
import com.jjmluz.photostv.Tab
import java.time.YearMonth

/** Left margin shared by the tab row and the pages below it. */
val PageStartPadding = 48.dp

private class TabEntry(val tab: Tab, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    TabEntry(Tab.Photos, "Fotos", Icons.Outlined.Photo),
    TabEntry(Tab.Albums, "Álbuns", Icons.Outlined.PhotoAlbum),
    TabEntry(Tab.People, "Pessoas", Icons.Outlined.People),
)

@Composable
fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        val selected = Tabs.indexOfFirst { it.tab == vm.tab }
        val requesters = remember { Tabs.map { FocusRequester() } }
        Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
            // A tonal pill holding the tabs, centred like the Google TV home screen's.
            TabRow(
                selectedTabIndex = selected,
                containerColor = Color.Transparent,
                indicator = { positions, rowFocused ->
                    TabRowDefaults.PillIndicator(
                        currentTabPosition = positions[selected],
                        doesTabRowHaveFocus = rowFocused,
                        activeColor = MaterialTheme.colorScheme.onSurface,
                        inactiveColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                },
                // Coming up from the page, land on the current tab rather than the nearest one.
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .padding(6.dp)
                    .focusRestorer(requesters[selected]),
            ) {
                Tabs.forEachIndexed { i, entry ->
                    Tab(
                        selected = i == selected,
                        // Switch as focus moves along the row; only a click re-selects the current
                        // tab, which leaves an open album or person.
                        onFocus = { if (vm.tab != entry.tab) vm.select(entry.tab) },
                        onClick = { vm.select(entry.tab) },
                        colors = TabDefaults.pillIndicatorTabColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            focusedSelectedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        ),
                        modifier = Modifier.focusRequester(requesters[i]),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(entry.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(entry.label, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            AccountButton(
                vm.profile,
                otherAccounts = vm.otherAccounts,
                onSwitch = vm::switchAccount,
                onAdd = vm::addAccount,
                onSignOut = vm::signOut,
                takeFocus = vm.refocusAccount.also { vm.refocusAccount = false },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = PageStartPadding),
            )
        }
        Box(Modifier.weight(1f)) {
            // Keep each tab's saveable state while it is off screen.
            val saveable = rememberSaveableStateHolder()
            saveable.SaveableStateProvider(vm.tab) {
                when (vm.tab) {
                    Tab.Photos -> PhotosTab(vm)
                    Tab.Albums -> AlbumsTab(vm)
                    Tab.People -> PeopleTab(vm)
                }
            }
        }
    }
}

@Composable
private fun PhotosTab(vm: MainViewModel) {
    val grid = vm.photos
    // On first launch, start with the newest photo focused instead of the tabs.
    val items by grid.feed.state.collectAsStateWithLifecycle()
    val hasItems = items.items.isNotEmpty()
    LaunchedEffect(hasItems) {
        if (hasItems && !vm.initialFocusDone) {
            vm.initialFocusDone = true
            grid.restoreKey = items.items.first().key
        }
    }
    val months by vm.months.months.collectAsStateWithLifecycle()
    val byKey = remember(items.items) { items.items.associateBy { it.key } }
    // The first photo on screen: sets the panel's current month and where focus returns on close.
    val firstVisible by remember(byKey) {
        derivedStateOf { grid.gridState.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { byKey[it.key] } }
    }
    Box(Modifier.fillMaxSize()) {
        MediaGridView(
            grid = grid,
            onOpen = { vm.openViewer(grid, it) },
            onSignIn = vm::signOut,
            groupByDay = true,
            modifier = Modifier.padding(end = TimelineRailWidth),
            endPadding = 12.dp,
        )
        if (months.isNotEmpty()) {
            TimelinePanel(
                months = months,
                current = firstVisible?.let { YearMonth.from(it.localDateTime) },
                onPick = vm::jumpTo,
                onClose = { grid.restoreKey = firstVisible?.key },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
