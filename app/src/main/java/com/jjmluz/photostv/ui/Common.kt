package com.jjmluz.photostv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.jjmluz.photostv.AppLocale
import com.jjmluz.photostv.data.MediaItem
import com.jjmluz.photostv.data.NotSignedInException

/** Thumbnail edge in pixels; a bit over the focused tile size on a 1080p UI. */
const val THUMB_PX = 400

/**
 * The sizes the viewer fetches photos at. The app window is 1080p even on a 4K TV (the system
 * scales it up), so a photo fetched at the window size shows almost at once; the panel's native
 * size (4K on the Streamer) is drawn into a surface of its own, which the TV shows unscaled.
 */
data class ViewerSizes(val windowW: Int, val windowH: Int, val panelW: Int, val panelH: Int) {
    val hasFullRes get() = panelW > windowW
}

fun MediaItem.screenUrl(sizes: ViewerSizes) = fullUrl(sizes.windowW, sizes.windowH)
fun MediaItem.fullResUrl(sizes: ViewerSizes) = fullUrl(sizes.panelW, sizes.panelH)

@Composable
fun rememberViewerSizes(): ViewerSizes {
    val context = LocalContext.current
    val window = LocalWindowInfo.current.containerSize
    return remember(window) {
        val mode = context.display.mode
        val panelW = maxOf(mode.physicalWidth, mode.physicalHeight).coerceAtMost(3840)
        val panelH = minOf(mode.physicalWidth, mode.physicalHeight).coerceAtMost(2160)
        ViewerSizes(window.width, window.height, maxOf(panelW, window.width), maxOf(panelH, window.height))
    }
}

val TileShape = RoundedCornerShape(16.dp)

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun MessageScreen(
    title: String,
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(
                    Modifier
                        .padding(bottom = 8.dp)
                        .size(88.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp))
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction, modifier = Modifier.focusRequester(focus)) { Text(action) }
                LaunchedEffect(Unit) { focus.requestFocusSafely() }
            }
        }
    }
}

/** Full-screen error for a feed that failed before showing anything. */
@Composable
fun FeedError(error: Throwable, onRetry: () -> Unit, onSignIn: () -> Unit) {
    if (error is NotSignedInException) {
        MessageScreen("A sessão expirou", "Inicie sessão novamente no Google Fotos.", "Iniciar sessão", onSignIn, Icons.Outlined.Lock)
    } else {
        MessageScreen("Não foi possível carregar", error.message ?: error.toString(), "Tentar novamente", onRetry, Icons.Outlined.CloudOff)
    }
}

@Composable
fun rememberImageRequest(url: String?, crossfade: Boolean = true): ImageRequest {
    val context = LocalContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            // Stable key so the viewer can show the cached thumbnail while the full image loads.
            .memoryCacheKey(url)
            .crossfade(crossfade)
            .build()
    }
}

/** A focusable, scaling image tile. */
@Composable
fun ImageTile(
    url: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = TileShape,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = shape),
        ),
    ) {
        AsyncImage(
            model = rememberImageRequest(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        overlay()
    }
}

@Composable
fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Capitalizes the first letter, e.g. "terça-feira" as a heading. */
fun String.capitalized() = replaceFirstChar { it.titlecase(AppLocale) }

/** The focused key after [key]'s tile gains or loses focus, given the [current] one. */
fun trackFocus(current: String?, key: String, focused: Boolean): String? = when {
    focused -> key
    current == key -> null
    else -> current
}

/** Requests focus once the target has been laid out; ignores targets that went away. */
suspend fun FocusRequester.requestFocusSafely() {
    withFrameNanos { }
    runCatching { requestFocus() }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
