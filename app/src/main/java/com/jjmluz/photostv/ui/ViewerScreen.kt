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
import androidx.compose.runtime.mutableLongStateOf
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.toBitmap
import com.jjmluz.photostv.AppLocale
import com.jjmluz.photostv.Viewer
import com.jjmluz.photostv.app
import com.jjmluz.photostv.data.MediaItem
import com.jjmluz.photostv.data.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private const val TAG = "PhotosTV"
private const val SLIDESHOW_INTERVAL_MS = 5_000L
private const val OVERLAY_TIMEOUT_MS = 4_000L
private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val SEEK_STEP_MS = 10_000L
private const val SEEK_FAST_MS = 30_000L
private const val SEEK_RUN_RESET_MS = 1_200L
private const val FULL_RES_FADE_MS = 300
private val DateFormat = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy · HH:mm", AppLocale)

/**
 * Full-screen viewer. Left/right move between items, up/down toggle the date and place,
 * center plays a video, play/pause runs a slideshow, back returns to the grid.
 */
@Composable
fun ViewerScreen(viewer: Viewer, locationOf: suspend (MediaItem) -> String?, onClose: (lastKey: String?) -> Unit) {
    val state by viewer.grid.feed.state.collectAsStateWithLifecycle()
    val items = state.items
    var index by remember { mutableIntStateOf(viewer.startIndex) }
    var overlay by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<MediaItem?>(null) }
    val item = items.getOrNull(index)
    var location by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    val sizes = rememberViewerSizes()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fullRes = remember(sizes) { if (sizes.hasFullRes) FullResImages(context, scope, sizes) else null }
    val surface = remember(sizes) { FullResSurface() }
    fun screenRequest(it: MediaItem) = ImageRequest.Builder(context).data(it.screenUrl(sizes)).build()

    fun go(delta: Int): Boolean {
        val next = index + delta
        if (next !in items.indices) return false
        index = next
        return true
    }

    BackHandler {
        when {
            playing != null -> playing = null
            else -> onClose(item?.key)
        }
    }

    LaunchedEffect(index) { Log.d(TAG, "Viewer: index $index video=${item?.isVideo}") }

    // The photo on screen loads first; then its neighbours, one at a time so they don't take
    // its bandwidth, and moving left/right is instant. Also keeps the feed ahead of us.
    LaunchedEffect(index, items.size) {
        if (index >= items.size - 10) viewer.grid.feed.loadMore()
        val loader = SingletonImageLoader.get(context)
        fullRes?.retain((index - 1..index + 1).mapNotNull { items.getOrNull(it)?.key }.toSet())
        listOf(index, index + 1, index - 1).forEach { i ->
            val near = items.getOrNull(i) ?: return@forEach
            when {
                fullRes != null && !near.isVideo -> fullRes.load(near).join()
                i != index -> loader.execute(screenRequest(near))
            }
        }
        items.getOrNull(index + 2)?.let { loader.execute(screenRequest(it)) }
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

    LaunchedEffect(slideshow, index) {
        if (!slideshow) return@LaunchedEffect
        delay(SLIDESHOW_INTERVAL_MS)
        // Wrap around at the end of what has loaded so far.
        if (!go(1)) index = 0
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> { slideshow = false; go(1); true }
                    Key.DirectionLeft -> { slideshow = false; go(-1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (item?.isVideo == true) playing = item
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> { overlay = !overlay; true }
                    Key.MediaPlayPause, Key.MediaPlay -> {
                        slideshow = !slideshow
                        overlay = false
                        true
                    }
                    Key.MediaPause, Key.MediaStop -> { slideshow = false; true }
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
                    update = { surface.bitmap = full },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            key(item.key) {
                // Over it, the thumbnail and then the 1080p image, until the 4K one is on screen. When
                // it was ready on arrival they're skipped; otherwise the 4K layer fades in over them.
                val readyOnArrival = remember { full != null }
                val sharp = full != null && surface.shown === full
                val coverAlpha by animateFloatAsState(if (sharp) 0f else 1f, tween(FULL_RES_FADE_MS), label = "cover")
                if (!readyOnArrival) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }.background(Color.Black)) {
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (item.isVideo) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(96.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Reproduzir", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
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
        }
    }

    LaunchedEffect(playing) { if (playing == null) focus.requestFocusSafely() }

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
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer, strokeWidth = 4.dp, modifier = Modifier.size(52.dp))
                }
                seekRun != 0L -> SeekChip(seekRun)
                paused -> CenterDisc {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
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

@Composable
private fun CenterDisc(content: @Composable () -> Unit) {
    Box(
        Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SeekChip(deltaMs: Long) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f), CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (deltaMs > 0) Icons.Filled.FastForward else Icons.Filled.FastRewind,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(28.dp),
        )
        Text(
            (if (deltaMs > 0) "+" else "−") + formatDuration(kotlin.math.abs(deltaMs)),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
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
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Em pausa" else "A reproduzir",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
    val primary = MaterialTheme.colorScheme.primary
    val track = Color.White.copy(alpha = 0.25f)
    val loaded = Color.White.copy(alpha = 0.45f)
    Canvas(modifier.height(16.dp)) {
        val y = size.height / 2
        val stroke = 6.dp.toPx()
        fun line(to: Float, color: Color) = drawLine(color, Offset(0f, y), Offset(size.width * to.coerceIn(0f, 1f), y), stroke, StrokeCap.Round)
        line(1f, track)
        line(buffered, loaded)
        line(played, primary)
        drawCircle(primary, radius = 8.dp.toPx(), center = Offset(size.width * played.coerceIn(0f, 1f), y))
    }
}

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
 * Photos at the panel's native size, for the photo on screen and its neighbours. They're kept
 * here rather than in Coil's memory cache, where three 4K bitmaps would push out the thumbnails.
 */
private class FullResImages(private val context: Context, private val scope: CoroutineScope, private val sizes: ViewerSizes) {
    val bitmaps = mutableStateMapOf<String, Bitmap>()
    private val jobs = HashMap<String, Job>()

    fun load(item: MediaItem): Job = jobs.getOrPut(item.key) {
        scope.launch {
            val start = System.currentTimeMillis()
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
 * Draws [bitmap] into a SurfaceView sized to the panel. The TV composes that surface at its
 * native resolution, unlike the app window, which it scales up from 1080p.
 */
private class FullResSurface : SurfaceHolder.Callback {
    private var holder: SurfaceHolder? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    var bitmap: Bitmap? = null
        set(value) {
            if (field === value && shown === value) return
            field = value
            draw()
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
        val bmp = bitmap
        val canvas = surface.lockHardwareCanvas()
        canvas.drawColor(android.graphics.Color.BLACK)
        if (bmp != null) {
            val scale = minOf(frame.width().toFloat() / bmp.width, frame.height().toFloat() / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = (frame.width() - w) / 2
            val top = (frame.height() - h) / 2
            canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), paint)
        }
        surface.unlockCanvasAndPost(canvas)
        shown = bmp
    }
}
