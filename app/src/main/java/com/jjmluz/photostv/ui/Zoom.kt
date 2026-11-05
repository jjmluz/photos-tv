package com.jjmluz.photostv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import com.jjmluz.photostv.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TAG = "PhotosTV"

/** The zoom levels OK steps through on a photo, back to the whole photo after the last. */
private val ZOOM_LEVELS = floatArrayOf(1f, 2.5f, 5f)
private const val ZOOM_MS = 300
/** How far a press of the d-pad moves the view, as a fraction of what's on screen; less per repeat while held. */
private const val PAN_STEP = 0.25f
private const val PAN_HELD_STEP = 0.08f
private val PanSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
/** How long the view rests before the sharp detail for it is decoded. */
private const val DETAIL_DELAY_MS = 80L
/** The detail covers a little more than the screen, so a pan starts sharp. */
private const val DETAIL_MARGIN = 0.15f
/** Kept within what any TV's GPU can take as one texture. */
private const val MAX_DETAIL_PX = 4096
/** The largest side Google serves a photo at, WebP's limit. */
private const val MAX_SOURCE_PX = 16383

/** The photo at its original size (when known), which zooming reads small parts of at a time. */
fun MediaItem.zoomUrl(sizes: ViewerSizes): String {
    if (width <= 0 || height <= 0) return fullUrl(sizes.panelW * 3, sizes.panelH * 3)
    val scale = minOf(1f, MAX_SOURCE_PX.toFloat() / maxOf(width, height))
    return fullUrl((width * scale).toInt(), (height * scale).toInt())
}

/**
 * Which part of a photo fills the screen at [zoom]: centred on ([cx], [cy]) in the photo's 0..1
 * coordinates, moved in as far as needed to keep the photo's edges from coming into view. Along a
 * side where the zoomed photo is still narrower than the screen, it stays centred.
 */
class ZoomView(val zoom: Float, cx: Float, cy: Float, photoAspect: Float, screenAspect: Float) {
    // The photo's size at 1x, as fractions of the screen's width and height (fitted, as the viewer shows it).
    private val fitW = minOf(1f, photoAspect / screenAspect)
    private val fitH = minOf(1f, screenAspect / photoAspect)

    /** Half the photo's width and height that show on screen, in its 0..1 coordinates. */
    val halfW = minOf(0.5f, 0.5f / (fitW * zoom))
    val halfH = minOf(0.5f, 0.5f / (fitH * zoom))
    val cx = cx.coerceIn(halfW, 1 - halfW)
    val cy = cy.coerceIn(halfH, 1 - halfH)

    /** The part of the photo on screen, in its 0..1 coordinates. */
    val visible get() = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

    /** Where the whole photo lands on a screen of [w]×[h] pixels, mostly past its edges. */
    fun photoRect(w: Float, h: Float): RectF {
        val pw = fitW * zoom * w
        val ph = fitH * zoom * h
        val left = w / 2 - cx * pw
        val top = h / 2 - cy * ph
        return RectF(left, top, left + pw, top + ph)
    }

    /** Where [area] of the photo, in its 0..1 coordinates, lands on a screen of [w]×[h] pixels. */
    fun map(area: RectF, w: Float, h: Float): RectF {
        val p = photoRect(w, h)
        return RectF(
            p.left + area.left * p.width(),
            p.top + area.top * p.height(),
            p.left + area.right * p.width(),
            p.top + area.bottom * p.height(),
        )
    }
}

/**
 * The viewer's zoom on one photo: OK steps through [ZOOM_LEVELS], the d-pad moves around the
 * zoomed photo. Animated, so both the 1080p image and the 4K layer follow it frame by frame.
 */
class PhotoZoom(private val scope: CoroutineScope, private val photoAspect: Float, private val screenAspect: Float) {
    var level by mutableIntStateOf(0)
        private set
    private val factor = Animatable(1f)
    private val cx = Animatable(0.5f)
    private val cy = Animatable(0.5f)

    val zoomed get() = level > 0
    val label get() = ZOOM_LEVELS[level].let { if (it % 1f == 0f) "${it.toInt()}×" else "${it.toString().replace('.', ',')}×" }

    private fun viewOf(zoom: Float, x: Float, y: Float) = ZoomView(zoom, x, y, photoAspect, screenAspect)

    /** What's on screen now, or null when the whole photo shows. */
    val view: ZoomView?
        get() = if (level == 0 && factor.value <= 1.0001f) null else viewOf(factor.value, cx.value, cy.value)

    /** Where the view is heading, so its sharp detail can load before it gets there. */
    val target: ZoomView?
        get() = if (level == 0) null else viewOf(ZOOM_LEVELS[level], cx.targetValue, cy.targetValue)

    /** The next zoom level, keeping the same spot in the middle; after the last, the whole photo. */
    fun cycle() {
        level = (level + 1) % ZOOM_LEVELS.size
        val zoom = ZOOM_LEVELS[level]
        val next = if (level == 0) viewOf(1f, 0.5f, 0.5f) else viewOf(zoom, cx.targetValue, cy.targetValue)
        scope.launch { factor.animateTo(zoom, tween(ZOOM_MS)) }
        scope.launch { cx.animateTo(next.cx, tween(ZOOM_MS)) }
        scope.launch { cy.animateTo(next.cy, tween(ZOOM_MS)) }
    }

    /** Back to the whole photo, animated. */
    fun zoomOut() {
        if (level == 0) return
        level = ZOOM_LEVELS.size - 1
        cycle()
    }

    /** Moves the view by [dx], [dy] steps (-1, 0 or 1), smaller ones while the key is [held]. */
    fun pan(dx: Int, dy: Int, held: Boolean) {
        val now = target ?: return
        val step = if (held) PAN_HELD_STEP else PAN_STEP
        val next = viewOf(now.zoom, now.cx + dx * 2 * now.halfW * step, now.cy + dy * 2 * now.halfH * step)
        if (dx != 0) scope.launch { cx.animateTo(next.cx, PanSpring) }
        if (dy != 0) scope.launch { cy.animateTo(next.cy, PanSpring) }
    }
}

@Composable
fun rememberPhotoZoom(item: MediaItem?, sizes: ViewerSizes, scope: CoroutineScope): PhotoZoom =
    remember(item?.key, sizes) {
        val screen = sizes.windowW.toFloat() / sizes.windowH
        val aspect = item?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: screen
        PhotoZoom(scope, aspect, screen)
    }

/** Scales and moves a full-screen, fitted image to show [view] (when there is one). */
fun Modifier.zoomed(view: () -> ZoomView?) = graphicsLayer {
    val v = view() ?: return@graphicsLayer
    val photo = v.photoRect(size.width, size.height)
    scaleX = v.zoom
    scaleY = v.zoom
    translationX = photo.centerX() - size.width / 2
    translationY = photo.centerY() - size.height / 2
}

/** Part of a photo, [area] in its 0..1 coordinates, decoded from the original at the screen's resolution. */
class ZoomDetail(val bitmap: Bitmap, val area: RectF)

/** The original photo, in the image disk cache, read a region at a time. */
private class ZoomSource(private val decoder: BitmapRegionDecoder, private val snapshot: DiskCache.Snapshot) {
    private val width = decoder.width
    private val height = decoder.height

    /** The part of the photo around [view], at the size it takes on a screen of [screenW]×[screenH] pixels. */
    fun decode(view: ZoomView, screenW: Int, screenH: Int): ZoomDetail? {
        val margin = minOf(DETAIL_MARGIN, (MAX_DETAIL_PX.toFloat() / maxOf(screenW, screenH) - 1) / 2).coerceAtLeast(0f)
        val visible = view.visible
        val area = RectF(
            (visible.left - visible.width() * margin).coerceAtLeast(0f),
            (visible.top - visible.height() * margin).coerceAtLeast(0f),
            (visible.right + visible.width() * margin).coerceAtMost(1f),
            (visible.bottom + visible.height() * margin).coerceAtMost(1f),
        )
        val src = Rect(
            floor(area.left * width).toInt(),
            floor(area.top * height).toInt(),
            ceil(area.right * width).toInt().coerceAtMost(width),
            ceil(area.bottom * height).toInt().coerceAtMost(height),
        )
        if (src.isEmpty) return null
        // Its size on screen, within the GPU's limit.
        val photo = view.photoRect(screenW.toFloat(), screenH.toFloat())
        val fit = minOf(1f, MAX_DETAIL_PX / (area.width() * photo.width()), MAX_DETAIL_PX / (area.height() * photo.height()))
        val outW = (area.width() * photo.width() * fit).roundToInt().coerceAtLeast(1)
        val outH = (area.height() * photo.height() * fit).roundToInt().coerceAtLeast(1)
        // Subsample as far as stays at least as sharp as the screen, then scale down the rest of the way.
        var sample = 1
        while (src.width() / (sample * 2) >= outW && src.height() / (sample * 2) >= outH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = decoder.decodeRegion(src, options) ?: return null
        if (bitmap.width > outW * 5 / 4) bitmap = Bitmap.createScaledBitmap(bitmap, outW, outH, true)
        return ZoomDetail(bitmap, RectF(src.left.toFloat() / width, src.top.toFloat() / height, src.right.toFloat() / width, src.bottom.toFloat() / height))
    }

    fun close() {
        decoder.recycle()
        snapshot.close()
    }
}

/** Downloads [url] into the image disk cache, if it isn't there yet, and opens it for region decoding. */
private suspend fun openZoomSource(context: Context, url: String): ZoomSource? = withContext(Dispatchers.IO) {
    val loader = SingletonImageLoader.get(context)
    val cache = loader.diskCache ?: return@withContext null
    val start = System.currentTimeMillis()
    val snapshot = cache.openSnapshot(url) ?: run {
        // Coil always decodes what it fetches, so ask for the smallest bitmap it can make.
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(1, 1)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        loader.execute(request)
        cache.openSnapshot(url)
    } ?: return@withContext null
    val decoder = runCatching { BitmapRegionDecoder.newInstance(snapshot.data.toFile().path) }
        .onFailure { Log.w(TAG, "Zoom: can't read the original: $it") }
        .getOrNull()
    if (decoder == null) {
        snapshot.close()
        return@withContext null
    }
    Log.d(TAG, "Zoom: original ${decoder.width}x${decoder.height} ready in ${System.currentTimeMillis() - start} ms")
    ZoomSource(decoder, snapshot)
}

/**
 * While [zoom] is zoomed in, the sharp part of the original around where it's heading, for a screen
 * of [screenW]×[screenH] pixels. Until it arrives (and past its edges while panning) the viewer
 * shows its 4K or 1080p image, scaled up.
 */
@Composable
fun rememberZoomDetail(item: MediaItem?, zoom: PhotoZoom, sizes: ViewerSizes, screenW: Int, screenH: Int): State<ZoomDetail?> {
    val context = LocalContext.current
    val state = remember(zoom) { mutableStateOf<ZoomDetail?>(null) }
    var detail by state
    LaunchedEffect(zoom, zoom.zoomed) {
        detail = null
        if (!zoom.zoomed || item == null || item.isVideo) return@LaunchedEffect
        val source = openZoomSource(context, item.zoomUrl(sizes)) ?: return@LaunchedEffect
        try {
            snapshotFlow { zoom.target }.collectLatest { view ->
                view ?: return@collectLatest
                delay(DETAIL_DELAY_MS)
                // A decode isn't interrupted when the view moves on: the source stays open until it ends.
                detail = withContext(Dispatchers.Default) { runCatching { source.decode(view, screenW, screenH) }.getOrNull() } ?: detail
            }
        } finally {
            source.close()
        }
    }
    return state
}

/** [detail] drawn where [view] puts it, for the window (the 4K layer draws its own). */
@Composable
fun ZoomDetailImage(detail: ZoomDetail?, view: () -> ZoomView?) {
    val d = detail ?: return
    val image = remember(d) { d.bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val v = view() ?: return@Canvas
        val r = v.map(d.area, size.width, size.height)
        drawImage(
            image,
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width().roundToInt(), r.height().roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/** The whole photo in miniature, with the part on screen outlined, and the zoom level above it. */
@Composable
fun ZoomMap(item: MediaItem, zoom: PhotoZoom, modifier: Modifier = Modifier) {
    val aspect = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1.5f
    val maxW = 240.dp
    val maxH = 150.dp
    val (w, h) = if (aspect >= maxW / maxH) maxW to maxW / aspect else maxH * aspect to maxH
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            zoom.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .size(w, h)
                .clip(shape)
                .border(1.dp, Color.White.copy(alpha = 0.6f), shape)
                .background(Color.Black),
        ) {
            AsyncImage(
                model = rememberImageRequest(item.thumbnailUrl(THUMB_PX), crossfade = false),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                val v = zoom.view?.visible ?: return@Canvas
                val left = v.left * size.width
                val top = v.top * size.height
                val right = v.right * size.width
                val bottom = v.bottom * size.height
                val shade = Color.Black.copy(alpha = 0.55f)
                // Dim everything outside the part on screen.
                drawRect(shade, Offset.Zero, Size(size.width, top))
                drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
                drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            }
        }
    }
}
