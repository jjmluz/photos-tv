package com.jjmluz.photostv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.toBitmap
import com.jjmluz.photostv.AppLocale
import com.jjmluz.photostv.Viewer
import com.jjmluz.photostv.app
import com.jjmluz.photostv.data.BackgroundMusic
import com.jjmluz.photostv.data.SlideshowMode
import com.jjmluz.photostv.data.MediaItem
import com.jjmluz.photostv.data.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

private const val TAG = "PhotosTV"
private const val SLIDESHOW_STATUS_MS = 3_000L
private const val OVERLAY_TIMEOUT_MS = 4_000L
private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val SEEK_STEP_MS = 10_000L
private const val SEEK_FAST_MS = 30_000L
private const val SEEK_RUN_RESET_MS = 1_200L
private const val FULL_RES_FADE_MS = 300
private const val DISSOLVE_MS = 700L
/** How many photos a slideshow downloads ahead of the one showing, and how many at a time. */
private const val PRELOAD_AHEAD = 3
private const val PARALLEL_DOWNLOADS = 2
/** How long a slideshow holds a photo for the next one's 4K version, before settling for 1080p. */
private const val FULL_RES_WAIT_MS = 3_000L
/** How long a slideshow waits for a photo at all, before giving up on it and trying the next. */
private const val READY_LIMIT_MS = 15_000L
/** How much the dynamic slideshow zooms each photo, in or out, over its time on screen. */
private const val KEN_BURNS_ZOOM = 0.08f
private val DateFormat = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy · HH:mm", AppLocale)

/**
 * Full-screen viewer. Left/right move between items, up/down toggle the date and place,
 * OK plays a video or zooms a photo (2.5×, 5×, then back; the d-pad then moves around it), holding OK (or the menu key) opens the slideshow menu, play/pause runs the
 * slideshow from a photo, back returns to the grid.
 *
 * The slideshow shows photos only, skipping videos: OK or play/pause pauses it, left/right step
 * between photos, back ends it on the photo showing. A photo stays until the next one is ready, so
 * slow ones stretch the pace rather than leave the screen black. In its dynamic mode the photos
 * dissolve into each other and each one zooms slowly, in and out by turns.
 */
@Composable
fun ViewerScreen(viewer: Viewer, locationOf: suspend (MediaItem) -> String?, onClose: (lastKey: String?) -> Unit) {
    val state by viewer.grid.feed.state.collectAsStateWithLifecycle()
    val items = state.items
    var index by remember { mutableIntStateOf(viewer.startIndex) }
    var overlay by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // OK went down here (not in the grid that opened the viewer), so its release is a press.
    var okDown by remember { mutableStateOf(false) }
    // OK is still held after opening the menu: its repeats and release must not reach the menu.
    var swallowOk by remember { mutableStateOf(false) }
    // Bumped to show the slideshow's status for a moment.
    var status by remember { mutableIntStateOf(0) }
    var statusShown by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<MediaItem?>(null) }
    val item = items.getOrNull(index)
    var location by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    val sizes = rememberViewerSizes()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = remember { Downloads(context, scope) }
    val fullRes = remember(sizes) { if (sizes.hasFullRes) FullResImages(context, scope, sizes, downloads) else null }
    val surface = remember(sizes) { FullResSurface() }
    fun screenRequest(it: MediaItem) = ImageRequest.Builder(context).data(it.screenUrl(sizes)).build()
    val settings = context.app.slideshowSettings
    val music = remember { BackgroundMusic(context, context.app.http, context.app.jamendo, scope) }
    DisposableEffect(music) { onDispose { music.release() } }
    val dynamic = slideshow && settings.mode == SlideshowMode.Dynamic
    val motion = remember { Motion { settings.seconds * 1_000L + DISSOLVE_MS } }
    val zoom = rememberPhotoZoom(item, sizes, scope)
    // The sharp detail is drawn into the 4K layer when there is one, else into the window.
    val detail by rememberZoomDetail(
        item, zoom, sizes,
        screenW = if (fullRes != null) sizes.panelW else sizes.windowW,
        screenH = if (fullRes != null) sizes.panelH else sizes.windowH,
    )

    fun go(delta: Int): Boolean {
        val next = index + delta
        if (next !in items.indices) return false
        index = next
        return true
    }

    /** The nearest photo past [from] in the direction of [delta], skipping videos. */
    fun photoIndex(from: Int, delta: Int): Int? {
        var i = from + delta
        while (i in items.indices) {
            if (!items[i].isVideo) return i
            i += delta
        }
        return null
    }

    /** The photo after [from] in a slideshow, wrapping around at the end of what has loaded so far. */
    fun photoAfter(from: Int) = photoIndex(from, 1) ?: photoIndex(-1, 1)

    /**
     * Waits until [photo] can go on screen: in 4K, or at 1080p once 4K has taken [FULL_RES_WAIT_MS].
     * False if neither arrives within [READY_LIMIT_MS].
     */
    suspend fun awaitReady(photo: MediaItem): Boolean = withTimeoutOrNull(READY_LIMIT_MS) {
        fun sharp() = fullRes?.bitmaps?.containsKey(photo.key) == true
        if (sharp()) return@withTimeoutOrNull true
        val screen = SingletonImageLoader.get(context).execute(screenRequest(photo)) is SuccessResult
        if (fullRes != null) {
            fullRes.load(photo)
            withTimeoutOrNull(FULL_RES_WAIT_MS) { snapshotFlow { sharp() }.first { it } }
        }
        screen || sharp()
    } == true

    fun startSlideshow() {
        if (item == null || item.isVideo) return
        zoom.zoomOut()
        slideshow = true
        paused = false
        status++
    }

    fun togglePause() {
        paused = !paused
        if (!paused) status++
    }

    fun openMenu() {
        menu = true
        overlay = false
    }

    BackHandler {
        when {
            playing != null -> playing = null
            slideshow -> slideshow = false
            zoom.zoomed -> zoom.zoomOut()
            else -> onClose(item?.key)
        }
    }

    // Leaving the app pauses the slideshow, and with it the music.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { paused = true }

    LaunchedEffect(index) { Log.d(TAG, "Viewer: index $index video=${item?.isVideo}") }

    // The photo on screen loads first; then its neighbours, one at a time so they don't take
    // its bandwidth, and moving left/right is instant. Also keeps the feed ahead of us.
    // A slideshow instead downloads the photos ahead, past any videos: every 1080p version, the
    // fallback for a late 4K one, then the 4K versions, and only the next photo is decoded.
    LaunchedEffect(index, items.size, slideshow) {
        if (index >= items.size - 10) viewer.grid.feed.loadMore()
        if (slideshow) {
            val ahead = generateSequence(photoAfter(index)) { photoAfter(it) }
                .takeWhile { it != index }
                .take(PRELOAD_AHEAD)
                .toList()
            val next = ahead.firstOrNull()
            val previous = photoIndex(index, -1)
            val photos = ahead.map { items[it] }
            fun urls(photo: MediaItem) = listOfNotNull(photo.screenUrl(sizes), fullRes?.let { photo.fullResUrl(sizes) })
            downloads.retain((listOfNotNull(items.getOrNull(index)) + photos).flatMap(::urls).toSet())
            photos.forEach { downloads.fetch(it.screenUrl(sizes)) }
            if (fullRes != null) photos.forEach { downloads.fetch(it.fullResUrl(sizes)) }
            val near = listOfNotNull(index, next, previous).mapNotNull { items.getOrNull(it) }
            fullRes?.retain(near.map { it.key }.toSet())
            near.filterNot { it.isVideo }.forEach { fullRes?.load(it) }
            return@LaunchedEffect
        }
        downloads.retain(emptySet())
        val loader = SingletonImageLoader.get(context)
        val next = index + 1
        val previous = index - 1
        fullRes?.retain(listOf(previous, index, next).mapNotNull { items.getOrNull(it)?.key }.toSet())
        listOf(index, next, previous).forEach { i ->
            val near = items.getOrNull(i) ?: return@forEach
            when {
                fullRes != null && !near.isVideo -> fullRes.load(near).join()
                i != index -> loader.execute(screenRequest(near))
            }
        }
        items.getOrNull(next + 1)?.let { loader.execute(screenRequest(it)) }
    }

    // Look the place up ahead of time so it is ready when the overlay opens.
    LaunchedEffect(item?.key) {
        location = null
        location = item?.let { locationOf(it) }
    }

    LaunchedEffect(overlay, index, slideshow) {
        if (overlay && !slideshow) {
            delay(OVERLAY_TIMEOUT_MS)
            overlay = false
        }
    }

    // The timer for the photo showing, held while paused or with the menu open. When it ends the
    // photo stays until the next one is ready, passing over any that can't be loaded.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(slideshow, paused, menu, index, settings.seconds) {
        progress.snapTo(0f)
        if (!slideshow || paused || menu) return@LaunchedEffect
        progress.animateTo(1f, tween(settings.seconds * 1_000, easing = LinearEasing))
        var next = photoAfter(index)
        while (next != null && next != index && !awaitReady(items[next])) next = photoAfter(next)
        next?.let { index = it }
    }

    // The dynamic slideshow dissolves to each new photo, or cuts to it while paused, once it can
    // be drawn: the one before stays until then.
    LaunchedEffect(dynamic, item?.key) {
        if (!dynamic) return@LaunchedEffect motion.clear()
        val photo = item ?: return@LaunchedEffect
        if (fullRes?.bitmaps?.containsKey(photo.key) != true) {
            withTimeoutOrNull(READY_LIMIT_MS) { SingletonImageLoader.get(context).execute(screenRequest(photo)) }
        }
        motion.show(photo, dissolve = !paused)
    }

    // Its clock only runs with the slideshow, so pausing (or the menu) freezes the movement.
    LaunchedEffect(dynamic, paused, menu) {
        if (!dynamic || paused || menu) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            withFrameMillis { now ->
                motion.advance(now - last)
                last = now
            }
        }
    }

    // The 4K layer follows the zoom frame by frame, with the sharp detail over it.
    LaunchedEffect(zoom, fullRes) {
        if (fullRes == null) return@LaunchedEffect
        snapshotFlow { zoom.view to detail }.collect { (view, detail) -> surface.setZoom(view, detail) }
    }

    // The 4K layer redraws whenever the movement or the photos ready for it change.
    LaunchedEffect(dynamic, fullRes) {
        if (!dynamic || fullRes == null) return@LaunchedEffect
        snapshotFlow {
            val current = motion.current ?: return@snapshotFlow null
            val previous = motion.previous
            listOfNotNull(
                previous?.let { shot -> fullRes.bitmaps[shot.item.key]?.let { SurfaceLayer(it, motion.scale(shot)) } },
                fullRes.bitmaps[current.item.key]?.let { SurfaceLayer(it, motion.scale(current), motion.alpha) },
            )
        }.filterNotNull().collect { surface.drawLayers(it) }
    }

    LaunchedEffect(slideshow, paused, settings.music) {
        when {
            !slideshow -> music.stop()
            paused -> music.pause()
            else -> music.play(settings.music)
        }
    }

    // Each new track shows the status, which credits its artist.
    LaunchedEffect(music.track) { if (music.track != null && slideshow) status++ }
    LaunchedEffect(status) {
        if (status == 0) return@LaunchedEffect
        statusShown = true
        delay(SLIDESHOW_STATUS_MS)
        statusShown = false
    }

    // The photo whose cover has something to show, until when the 4K layer keeps the one before.
    var covered by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (!swallowOk || !event.key.isOk) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) swallowOk = false
                true
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    if (event.key.isOk) {
                        val repeats = event.nativeKeyEvent.repeatCount
                        when {
                            down && repeats == 0 -> okDown = true
                            // Held: the first repeat comes after the system's long-press delay.
                            down && okDown && repeats == 1 -> {
                                okDown = false
                                swallowOk = true
                                openMenu()
                            }
                            !down && okDown -> {
                                okDown = false
                                when {
                                    item?.isVideo == true -> playing = item
                                    slideshow -> togglePause()
                                    item != null -> {
                                        zoom.cycle()
                                        overlay = false
                                    }
                                }
                            }
                        }
                        return@onKeyEvent true
                    }
                    if (!down) return@onKeyEvent false
                    // Zoomed in, the d-pad moves around the photo instead.
                    if (zoom.zoomed && !slideshow) {
                        val held = event.nativeKeyEvent.repeatCount > 0
                        when (event.key) {
                            Key.DirectionRight -> { zoom.pan(1, 0, held); return@onKeyEvent true }
                            Key.DirectionLeft -> { zoom.pan(-1, 0, held); return@onKeyEvent true }
                            Key.DirectionUp -> { zoom.pan(0, -1, held); return@onKeyEvent true }
                            Key.DirectionDown -> { zoom.pan(0, 1, held); return@onKeyEvent true }
                            else -> {}
                        }
                    }
                    when (event.key) {
                        Key.DirectionRight -> { if (slideshow) photoIndex(index, 1)?.let { index = it } else go(1); true }
                        Key.DirectionLeft -> { if (slideshow) photoIndex(index, -1)?.let { index = it } else go(-1); true }
                        Key.DirectionUp, Key.DirectionDown -> { overlay = !overlay; true }
                        Key.Menu -> { openMenu(); true }
                        Key.MediaPlayPause -> {
                            when {
                                slideshow -> togglePause()
                                item?.isVideo == true -> playing = item
                                else -> startSlideshow()
                            }
                            true
                        }
                        Key.MediaPlay -> {
                            when {
                                slideshow && paused -> togglePause()
                                !slideshow -> startSlideshow()
                            }
                            true
                        }
                        Key.MediaPause -> { if (slideshow && !paused) togglePause(); true }
                        Key.MediaStop -> { slideshow = false; true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            if (item != null) {
                // Under everything, the 4K layer (left out while a video plays, which has its own surface).
                val full = if (item.isVideo) null else fullRes?.bitmaps?.get(item.key)
                if (fullRes != null && playing == null) {
                    AndroidView(
                        factory = { SurfaceView(it).apply { holder.setFixedSize(sizes.panelW, sizes.panelH); holder.addCallback(surface) } },
                        update = { if (!dynamic && (full != null || covered == item.key)) surface.bitmap = full },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (dynamic) {
                    // Photos without their 4K version yet (or on a 1080p screen), drawn here instead.
                    // The newer one stays on top, so it's drawn here too while the older one is.
                    val current = motion.current
                    val previous = motion.previous
                    val previousHere = previous != null && fullRes?.bitmaps?.get(previous.item.key) == null
                    if (previous != null && previousHere) {
                        key(previous.item.key) { MotionImage(previous.item, sizes, scale = { motion.scale(previous) }) }
                    }
                    if (current != null && (previousHere || fullRes?.bitmaps?.get(current.item.key) == null)) {
                        key(current.item.key) { MotionImage(current.item, sizes, scale = { motion.scale(current) }, alpha = { motion.alpha }) }
                    }
                } else {
                    key(item.key) {
                        // Over it, the thumbnail and then the 1080p image, until the 4K one is on screen. When
                        // it was ready on arrival they're skipped; otherwise the 4K layer fades in over them.
                        val readyOnArrival = remember { full != null }
                        val sharp = full != null && surface.shown === full
                        val coverAlpha by animateFloatAsState(if (sharp) 0f else 1f, tween(FULL_RES_FADE_MS), label = "cover")
                        if (!readyOnArrival) {
                            val backdrop = if (covered == item.key) Color.Black else Color.Transparent
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }.background(backdrop)) {
                                AsyncImage(
                                    model = remember(sizes) {
                                        ImageRequest.Builder(context)
                                            .data(item.screenUrl(sizes))
                                            .placeholderMemoryCacheKey(item.thumbnailUrl(THUMB_PX))
                                            .crossfade(true)
                                            .build()
                                    },
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    onState = { if (it.painter != null || it is AsyncImagePainter.State.Error) covered = item.key },
                                    modifier = Modifier.fillMaxSize().zoomed { zoom.view },
                                )
                            }
                        }
                    }
                    if (fullRes == null) ZoomDetailImage(detail) { zoom.view }
                }
                if (item.isVideo) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(96.dp)
                            .background(ControlColor.copy(alpha = 0.9f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Reproduzir", tint = OnControlColor, modifier = Modifier.size(56.dp))
                    }
                }
                AnimatedVisibility(
                    visible = overlay,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    InfoOverlay(item, location)
                }
                AnimatedVisibility(
                    visible = zoom.zoomed && !menu,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp),
                ) {
                    ZoomMap(item, zoom)
                }
                if (slideshow && paused && !menu) {
                    SlideshowPaused(music.track)
                }
                AnimatedVisibility(
                    visible = slideshow && !paused && !menu && statusShown,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    SlideshowStatus(settings.seconds, music.track, progress.value)
                }
            }
        }
        if (menu) {
            SlideshowMenu(
                settings = settings,
                canStart = item?.isVideo == false,
                running = slideshow,
                musicAvailable = context.app.jamendo.available,
                onStart = {
                    menu = false
                    if (slideshow) {
                        if (paused) togglePause()
                    } else {
                        startSlideshow()
                    }
                },
                onDismiss = { menu = false },
            )
        }
    }

    LaunchedEffect(playing, menu) { if (playing == null && !menu) focus.requestFocusSafely() }

    playing?.let { VideoPlayer(it, posterUrl = it.screenUrl(sizes), onEnded = { playing = null }) }
}

@Composable
private fun InfoOverlay(item: MediaItem, location: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(item.localDateTime.format(DateFormat).capitalized(), style = MaterialTheme.typography.titleSmall, color = Color.White)
        if (location != null) {
            // A tonal chip, as Material You marks secondary details.
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f), CircleShape)
                    .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
                Text(location, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/**
 * Full-screen playback with our own controls: left/right seek (faster while held), OK or
 * play/pause toggles pause, up/down show the progress bar. Back (handled by the viewer) closes it.
 */
@Composable
private fun VideoPlayer(item: MediaItem, posterUrl: String, onEnded: () -> Unit) {
    val context = LocalContext.current
    // Until the first frame is on screen, keep showing the photo with a spinner instead of black.
    var started by remember(item.key) { mutableStateOf(false) }
    var paused by remember(item.key) { mutableStateOf(false) }
    var buffering by remember(item.key) { mutableStateOf(true) }
    var position by remember(item.key) { mutableLongStateOf(0L) }
    var buffered by remember(item.key) { mutableLongStateOf(0L) }
    var duration by remember(item.key) { mutableLongStateOf(item.durationMs ?: 0L) }
    var controls by remember(item.key) { mutableStateOf(true) }
    // Bumped on every key press, restarting the timer that hides the controls.
    var interaction by remember(item.key) { mutableIntStateOf(0) }
    // Total of the current run of seeks, shown as "+30 s" until the presses stop.
    var seekRun by remember(item.key) { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    val sources = item.videoSources
    val player = remember(item.key) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(videoHttp(context))))
            .build()
            .apply {
                setMediaItem(sources.first().toMediaItem())
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) {
        var attempt = 0
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "Video: state $state")
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Video: source $attempt failed: ${error.errorCodeName}")
                if (++attempt < sources.size) {
                    // Try the next rendition, e.g. the original when no stream exists yet.
                    player.setMediaItem(sources[attempt].toMediaItem())
                    player.prepare()
                } else {
                    Toast.makeText(context, "Não é possível reproduzir este vídeo", Toast.LENGTH_LONG).show()
                    onEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            if (player.duration > 0) duration = player.duration
            delay(200)
        }
    }
    LaunchedEffect(interaction, paused) {
        if (!paused) {
            delay(CONTROLS_TIMEOUT_MS)
            controls = false
        }
    }
    LaunchedEffect(interaction) {
        delay(SEEK_RUN_RESET_MS)
        seekRun = 0
    }
    LaunchedEffect(Unit) { focus.requestFocusSafely() }

    fun seek(delta: Long) {
        val target = (player.currentPosition + delta).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        seekRun += target - player.currentPosition
        player.seekTo(target)
        position = target
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Held keys repeat; after a second or so, take bigger steps.
                val step = if (event.nativeKeyEvent.repeatCount > 8) SEEK_FAST_MS else SEEK_STEP_MS
                val handled = when (event.key) {
                    Key.DirectionRight, Key.MediaFastForward -> { seek(step); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-step); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        player.playWhenReady = !player.playWhenReady
                        true
                    }
                    Key.MediaPlay -> { player.playWhenReady = true; true }
                    Key.MediaPause -> { player.playWhenReady = false; true }
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
                if (handled) {
                    controls = true
                    interaction++
                }
                handled
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                }
            },
        )
        if (!started) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .placeholderMemoryCacheKey(item.thumbnailUrl(THUMB_PX))
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Centre: a spinner while loading, the seek total while seeking, a play sign while paused.
        Box(Modifier.align(Alignment.Center)) {
            when {
                !started || (buffering && seekRun == 0L) -> CenterDisc {
                    CircularProgressIndicator(color = OnControlColor, strokeWidth = 4.dp, modifier = Modifier.size(52.dp))
                }
                seekRun != 0L -> SeekChip(seekRun)
                paused -> CenterDisc {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = OnControlColor, modifier = Modifier.size(56.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = controls && started,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            VideoControls(position, buffered, duration, paused)
        }
    }
}

/** Video controls are white with dark icons, like the TV's own player, rather than the theme's blue. */
val ControlColor = Color.White
val OnControlColor = Color(0xFF1F1F1F)

@Composable
fun CenterDisc(content: @Composable () -> Unit) {
    Box(
        Modifier.size(96.dp).background(ControlColor.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SeekChip(deltaMs: Long) {
    Row(
        Modifier
            .background(ControlColor.copy(alpha = 0.9f), CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (deltaMs > 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
            contentDescription = null,
            tint = OnControlColor,
            modifier = Modifier.size(28.dp),
        )
        Text(
            (if (deltaMs > 0) "+" else "−") + formatDuration(kotlin.math.abs(deltaMs)),
            style = MaterialTheme.typography.titleLarge,
            color = OnControlColor,
        )
    }
}

@Composable
private fun VideoControls(position: Long, buffered: Long, duration: Long, paused: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(ControlColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Em pausa" else "A reproduzir",
                tint = OnControlColor,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(formatDuration(position), style = MaterialTheme.typography.titleSmall, color = Color.White)
        ProgressBar(
            played = if (duration > 0) position.toFloat() / duration else 0f,
            buffered = if (duration > 0) buffered.toFloat() / duration else 0f,
            modifier = Modifier.weight(1f),
        )
        Text(formatDuration(duration), style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.75f))
    }
}

/** A rounded track with the buffered part, the played part and a thumb at the playhead. */
@Composable
private fun ProgressBar(played: Float, buffered: Float, modifier: Modifier = Modifier) {
    val playedColor = ControlColor
    val track = Color.White.copy(alpha = 0.25f)
    val loaded = Color.White.copy(alpha = 0.45f)
    Canvas(modifier.height(16.dp)) {
        val y = size.height / 2
        val stroke = 6.dp.toPx()
        fun line(to: Float, color: Color) = drawLine(color, Offset(0f, y), Offset(size.width * to.coerceIn(0f, 1f), y), stroke, StrokeCap.Round)
        line(1f, track)
        line(buffered, loaded)
        line(played, playedColor)
        drawCircle(playedColor, radius = 8.dp.toPx(), center = Offset(size.width * played.coerceIn(0f, 1f), y))
    }
}

private val Key.isOk get() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

private fun VideoSource.toMediaItem() =
    ExoMediaItem.Builder().setUri(url).setMimeType(mimeType).build()

/** The app's client, logging each video response's status and type (never the URL). */
private fun videoHttp(context: Context) = context.app.http.newBuilder()
    .addNetworkInterceptor { chain ->
        chain.proceed(chain.request()).also {
            Log.i(TAG, "Video: HTTP ${it.code} from ${it.request.url.host} type=${it.header("Content-Type")} length=${it.header("Content-Length")}")
        }
    }
    .build()

/**
 * Images a slideshow will reach soon, downloaded into the disk cache in the order asked for. They
 * carry on as the slideshow moves, so a photo that takes longer than a slide still finishes.
 */
private class Downloads(private val context: Context, private val scope: CoroutineScope) {
    private val jobs = HashMap<String, Job>()
    private val slots = Semaphore(PARALLEL_DOWNLOADS)

    fun fetch(url: String) {
        jobs.getOrPut(url) {
            scope.launch {
                // Coil always decodes what it fetches, so ask for the smallest bitmap it can make.
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(1, 1)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
                val result = slots.withPermit { SingletonImageLoader.get(context).execute(request) }
                // Let a later visit try again.
                if (result !is SuccessResult) jobs.remove(url)
            }
        }
    }

    /** Waits for [url]'s download, if there is one. */
    suspend fun join(url: String) {
        jobs[url]?.join()
    }

    /** Cancels everything but [urls]. */
    fun retain(urls: Set<String>) {
        jobs.keys.filter { it !in urls }.forEach { jobs.remove(it)?.cancel() }
    }
}

/**
 * Photos at the panel's native size, for the photo on screen and its neighbours. They're kept
 * here rather than in Coil's memory cache, where three 4K bitmaps would push out the thumbnails.
 */
private class FullResImages(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sizes: ViewerSizes,
    private val downloads: Downloads,
) {
    val bitmaps = mutableStateMapOf<String, Bitmap>()
    private val jobs = HashMap<String, Job>()

    fun load(item: MediaItem): Job = jobs.getOrPut(item.key) {
        scope.launch {
            val start = System.currentTimeMillis()
            downloads.join(item.fullResUrl(sizes))
            val request = ImageRequest.Builder(context)
                .data(item.fullResUrl(sizes))
                .size(sizes.panelW, sizes.panelH)
                .precision(Precision.INEXACT)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build()
            when (val result = SingletonImageLoader.get(context).execute(request)) {
                is SuccessResult -> {
                    bitmaps[item.key] = result.image.toBitmap()
                    Log.d(TAG, "Viewer: full resolution ${result.image.width}x${result.image.height} in ${System.currentTimeMillis() - start} ms (${result.dataSource})")
                }
                // Let a later visit try again.
                else -> jobs.remove(item.key)
            }
        }
    }

    /** Drops everything but [keys], cancelling their downloads. */
    fun retain(keys: Set<String>) {
        jobs.keys.filter { it !in keys }.forEach { jobs.remove(it)?.cancel() }
        bitmaps.keys.filter { it !in keys }.forEach { bitmaps.remove(it) }
    }
}

/**
 * The dynamic slideshow's movement: each photo zooms slowly, in and out by turns, over its time on
 * screen ([zoomSpanMs], which includes fading out under the next one), and fades in over the one
 * before. Moves only as far as [advance] says, so it stops while the slideshow is paused.
 */
private class Motion(private val zoomSpanMs: () -> Long) {
    class Shot(val item: MediaItem, val zoomIn: Boolean) {
        var elapsedMs by mutableLongStateOf(0L)
    }

    var current by mutableStateOf<Shot?>(null)
        private set

    /** The photo fading out under [current], until the dissolve ends. */
    var previous by mutableStateOf<Shot?>(null)
        private set

    private var fade by mutableFloatStateOf(1f)

    /** [current]'s opacity, easing in and out of the dissolve. */
    val alpha get() = fade * fade * (3 - 2 * fade)

    fun show(item: MediaItem, dissolve: Boolean) {
        val last = current
        if (last?.item?.key == item.key) return
        current = Shot(item, zoomIn = !(last?.zoomIn ?: false))
        previous = last?.takeIf { dissolve }
        fade = if (previous != null) 0f else 1f
    }

    fun clear() {
        current = null
        previous = null
        fade = 1f
    }

    fun advance(ms: Long) {
        current?.let { it.elapsedMs += ms }
        val before = previous ?: return
        before.elapsedMs += ms
        fade = (fade + ms.toFloat() / DISSOLVE_MS).coerceAtMost(1f)
        if (fade >= 1f) previous = null
    }

    fun scale(shot: Shot): Float {
        val t = (shot.elapsedMs.toFloat() / zoomSpanMs()).coerceIn(0f, 1f)
        return 1f + KEN_BURNS_ZOOM * (if (shot.zoomIn) t else 1f - t)
    }
}

/** A photo of the dynamic slideshow at the window's resolution, for when its 4K version isn't there. */
@Composable
private fun MotionImage(item: MediaItem, sizes: ViewerSizes, scale: () -> Float, alpha: () -> Float = { 1f }) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(sizes) {
            ImageRequest.Builder(context)
                .data(item.screenUrl(sizes))
                .placeholderMemoryCacheKey(item.thumbnailUrl(THUMB_PX))
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        // The black fades in with the photo, so the one under it doesn't linger around a narrower one.
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val s = scale()
            scaleX = s
            scaleY = s
            this.alpha = alpha()
        }.background(Color.Black),
    )
}

/** A photo drawn into the 4K layer, fitted to the screen, then scaled about its centre. */
private class SurfaceLayer(val bitmap: Bitmap, val scale: Float = 1f, val alpha: Float = 1f)

/**
 * Draws [bitmap] into a SurfaceView sized to the panel. The TV composes that surface at its
 * native resolution, unlike the app window, which it scales up from 1080p.
 */
private class FullResSurface : SurfaceHolder.Callback {
    private var holder: SurfaceHolder? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var layers = emptyList<SurfaceLayer>()
    // The still photo's zoom, and the sharp part of it drawn over it.
    private var zoom: ZoomView? = null
    private var detail: ZoomDetail? = null
    // Showing [bitmap] as it is, rather than the dynamic slideshow's layers.
    private var still = true

    var bitmap: Bitmap? = null
        set(value) {
            if (still && field === value && shown === value) return
            field = value
            still = true
            layers = listOfNotNull(value?.let { SurfaceLayer(it) })
            draw()
        }

    /** Draws [layers], bottom first, until [bitmap] is set again. */
    fun drawLayers(layers: List<SurfaceLayer>) {
        still = false
        this.layers = layers
        draw()
    }

    /** Zooms the still photo to [view] (the whole photo when null), with [detail] over it. */
    fun setZoom(view: ZoomView?, detail: ZoomDetail?) {
        if (zoom == null && view == null) return
        zoom = view
        this.detail = detail
        if (still) draw()
    }

    /** What the surface shows now, once drawn. */
    var shown by mutableStateOf<Bitmap?>(null)
        private set

    override fun surfaceCreated(holder: SurfaceHolder) { this.holder = holder }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { draw() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        this.holder = null
        shown = null
    }

    private fun draw() {
        val surface = holder?.surface?.takeIf { it.isValid } ?: return
        val frame = holder!!.surfaceFrame
        val canvas = surface.lockHardwareCanvas()
        canvas.drawColor(android.graphics.Color.BLACK)
        val zoom = zoom.takeIf { still }
        for (layer in layers) {
            val bmp = layer.bitmap
            val rect = zoom?.photoRect(frame.width().toFloat(), frame.height().toFloat()) ?: run {
                val scale = minOf(frame.width().toFloat() / bmp.width, frame.height().toFloat() / bmp.height) * layer.scale
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = (frame.width() - w) / 2
                val top = (frame.height() - h) / 2
                RectF(left, top, left + w, top + h)
            }
            paint.alpha = (layer.alpha * 255).toInt()
            if (paint.alpha < 255) {
                // The screen around it fades to black with it, so the photo under it doesn't linger there.
                canvas.save()
                canvas.clipOutRect(rect)
                canvas.drawColor(android.graphics.Color.argb(paint.alpha, 0, 0, 0))
                canvas.restore()
            }
            canvas.drawBitmap(bmp, null, rect, paint)
        }
        val sharp = detail
        if (zoom != null && sharp != null && layers.isNotEmpty()) {
            paint.alpha = 255
            canvas.drawBitmap(sharp.bitmap, null, zoom.map(sharp.area, frame.width().toFloat(), frame.height().toFloat()), paint)
        }
        surface.unlockCanvasAndPost(canvas)
        shown = if (still) bitmap else null
    }
}
