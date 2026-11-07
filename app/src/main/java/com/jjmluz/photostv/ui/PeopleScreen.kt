package com.jjmluz.photostv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.MainViewModel
import com.jjmluz.photostv.R
import com.jjmluz.photostv.data.Person

@Composable
fun PeopleTab(vm: MainViewModel) {
    val open = vm.openPerson
    if (open != null) {
        MediaGridView(
            grid = open.grid,
            onOpen = { vm.openViewer(open.grid, it) },
            onSignIn = vm::signOut,
            groupByDay = true,
            title = { ScreenTitle(open.person.name ?: stringResource(R.string.person_unnamed), photoCount(open.person.itemCount)) },
        )
        // Focus the newest photo when entering the person's page.
        val state by open.grid.feed.state.collectAsStateWithLifecycle()
        val first = state.items.firstOrNull()?.key
        LaunchedEffect(open, first != null) {
            if (first != null && !open.focusedOnce) {
                open.focusedOnce = true
                open.grid.restoreKey = first
            }
        }
    } else {
        PeopleList(vm)
    }
}

@Composable
private fun PeopleList(vm: MainViewModel) {
    val feed = vm.people
    val state by feed.state.collectAsStateWithLifecycle()

    if (state.items.isEmpty()) {
        when {
            state.error != null -> FeedError(state.error!!, feed::refresh, vm::signOut)
            state.endReached -> MessageScreen(stringResource(R.string.people_empty), stringResource(R.string.people_empty_message), icon = Icons.Outlined.People)
            else -> LoadingScreen()
        }
        return
    }

    val restoreKey = vm.peopleRestoreKey
    LaunchedEffect(restoreKey) {
        val index = state.items.indexOfFirst { it.key == restoreKey }
        if (index >= 0 && vm.peopleGridState.layoutInfo.visibleItemsInfo.none { it.key == restoreKey }) {
            vm.peopleGridState.scrollToItem(index)
        }
    }
    LaunchedEffect(state.items.size) {
        snapshotFlow { vm.peopleGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (it >= state.items.size - 12) feed.loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        state = vm.peopleGridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageStartPadding, end = 48.dp, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(state.items, key = { it.key }) { person ->
            val focus = remember { FocusRequester() }
            if (person.key == restoreKey) {
                LaunchedEffect(restoreKey) {
                    focus.requestFocusSafely()
                    vm.peopleRestoreKey = null
                }
            }
            PersonCard(
                person,
                onClick = { vm.openPerson(person) },
                modifier = Modifier
                    .focusRequester(focus)
                    .onFocusChanged { vm.peopleFocusedKey = trackFocus(vm.peopleFocusedKey, person.key, it.hasFocus) },
            )
        }
    }
}

@Composable
private fun PersonCard(person: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ImageTile(
            url = person.faceUrl(THUMB_PX),
            onClick = onClick,
            shape = CircleShape,
            modifier = modifier.fillMaxWidth().aspectRatio(1f),
            overlay = {
                if (person.faceUrl == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    }
                }
            },
        )
        // Unnamed face groups show only their count, as in the official app.
        if (person.name != null) {
            Text(
                person.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            photoCount(person.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (person.name == null) Modifier.padding(top = 6.dp) else Modifier,
        )
    }
}

@Composable
private fun photoCount(n: Int) = pluralStringResource(R.plurals.item_count, n, n)
