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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.MainViewModel
import com.jjmluz.photostv.R
import com.jjmluz.photostv.data.Album

@Composable
fun AlbumsTab(vm: MainViewModel) {
    val open = vm.openAlbum
    if (open != null) {
        MediaGridView(
            grid = open.grid,
            onOpen = { vm.openViewer(open.grid, it) },
            onSignIn = vm::signOut,
            groupByDay = false,
            title = { ScreenTitle(open.album.title, itemCount(open.album.itemCount)) },
        )
        // Focus the first photo when entering the album.
        val state by open.grid.feed.state.collectAsStateWithLifecycle()
        val first = state.items.firstOrNull()?.key
        LaunchedEffect(open, first != null) {
            if (first != null && !open.focusedOnce) {
                open.focusedOnce = true
                open.grid.restoreKey = first
            }
        }
    } else {
        AlbumList(vm)
    }
}

@Composable
private fun AlbumList(vm: MainViewModel) {
    val feed = vm.albums
    val state by feed.state.collectAsStateWithLifecycle()

    if (state.items.isEmpty()) {
        when {
            state.error != null -> FeedError(state.error!!, feed::refresh, vm::signOut)
            state.endReached -> MessageScreen(stringResource(R.string.albums_empty), stringResource(R.string.albums_empty_message), icon = Icons.Outlined.PhotoAlbum)
            else -> LoadingScreen()
        }
        return
    }

    val restoreKey = vm.albumsRestoreKey
    LaunchedEffect(restoreKey) {
        val index = state.items.indexOfFirst { it.key == restoreKey }
        if (index >= 0 && vm.albumsGridState.layoutInfo.visibleItemsInfo.none { it.key == restoreKey }) {
            vm.albumsGridState.scrollToItem(index)
        }
    }
    LaunchedEffect(state.items.size) {
        snapshotFlow { vm.albumsGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (it >= state.items.size - 10) feed.loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = vm.albumsGridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageStartPadding, end = 48.dp, top = 24.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(state.items, key = { it.key }) { album ->
            val focus = remember { FocusRequester() }
            if (album.key == restoreKey) {
                LaunchedEffect(restoreKey) {
                    focus.requestFocusSafely()
                    vm.albumsRestoreKey = null
                }
            }
            AlbumCard(
                album,
                onClick = { vm.openAlbum(album) },
                modifier = Modifier
                    .focusRequester(focus)
                    .onFocusChanged { vm.albumsFocusedKey = trackFocus(vm.albumsFocusedKey, album.key, it.hasFocus) },
            )
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ImageTile(
            url = album.coverUrl(THUMB_PX),
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            modifier = modifier.fillMaxWidth().aspectRatio(1f),
            overlay = {
                if (album.coverUrl == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    }
                }
            },
        )
        Text(
            album.title.ifBlank { stringResource(R.string.album_untitled) },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            itemCount(album.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun itemCount(n: Int) = pluralStringResource(R.plurals.item_count, n, n)
