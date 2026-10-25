package com.jjmluz.photostv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.jjmluz.photostv.data.SlideshowMusic
import com.jjmluz.photostv.data.SlideshowSettings
import com.jjmluz.photostv.data.Track

private enum class MenuPage { Main, Duration, Music }

private fun secondsLabel(seconds: Int) = "$seconds segundos"

/**
 * The viewer's options, a panel on the right opened by holding OK: start the slideshow, and pick
 * how long each photo shows and the music. Back returns from a sub-page, then closes it.
 */
@Composable
fun SlideshowMenu(
    settings: SlideshowSettings,
    canStart: Boolean,
    running: Boolean,
    musicAvailable: Boolean,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(MenuPage.Main) }
    // The main page's row to focus on arrival: the one a sub-page was opened from.
    var returnTo by remember { mutableStateOf(MenuPage.Main) }
    BackHandler {
        if (page == MenuPage.Main) {
            onDismiss()
        } else {
            returnTo = page
            page = MenuPage.Main
        }
    }
    fun open(sub: MenuPage) { page = sub }
    fun back() {
        returnTo = page
        page = MenuPage.Main
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(0.3f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.55f))),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxHeight()
                .width(340.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f), RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 20.dp)
                // Focus stays in the panel until it closes.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (page) {
                MenuPage.Main -> MainPage(settings, canStart, running, returnTo, onStart, ::open)
                MenuPage.Duration -> {
                    PageTitle("Duração de cada foto")
                    val focus = remember { FocusRequester() }
                    SlideshowSettings.Durations.forEach { seconds ->
                        val selected = seconds == settings.seconds
                        OptionRow(
                            label = secondsLabel(seconds),
                            selected = selected,
                            onClick = { settings.seconds = seconds; back() },
                            modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                    LaunchedEffect(Unit) { focus.requestFocusSafely() }
                }
                MenuPage.Music -> {
                    PageTitle("Música de fundo")
                    val focus = remember { FocusRequester() }
                    SlideshowMusic.entries.forEach { music ->
                        val selected = music == settings.music
                        val enabled = music.tag == null || musicAvailable
                        OptionRow(
                            label = music.label,
                            detail = if (enabled) music.detail else "Precisa de uma chave da Jamendo",
                            selected = selected,
                            enabled = enabled,
                            onClick = { settings.music = music; back() },
                            modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Música da Jamendo, com licenças Creative Commons",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    LaunchedEffect(Unit) { focus.requestFocusSafely() }
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    settings: SlideshowSettings,
    canStart: Boolean,
    running: Boolean,
    returnTo: MenuPage,
    onStart: () -> Unit,
    onOpen: (MenuPage) -> Unit,
) {
    val start = remember { FocusRequester() }
    val duration = remember { FocusRequester() }
    val music = remember { FocusRequester() }
    PageTitle("Slideshow")
    ListItem(
        selected = false,
        enabled = canStart,
        onClick = onStart,
        headlineContent = { Text(if (running) "Continuar slideshow" else "Iniciar slideshow") },
        supportingContent = { Text(if (canStart) "A partir desta foto" else "Escolha uma foto: os vídeos ficam de fora") },
        leadingContent = {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        },
        modifier = Modifier.focusRequester(start),
    )
    SettingRow(Icons.Outlined.Timer, "Duração de cada foto", secondsLabel(settings.seconds), { onOpen(MenuPage.Duration) }, Modifier.focusRequester(duration))
    SettingRow(Icons.Outlined.MusicNote, "Música de fundo", settings.music.label, { onOpen(MenuPage.Music) }, Modifier.focusRequester(music))
    LaunchedEffect(Unit) {
        when {
            returnTo == MenuPage.Duration -> duration
            returnTo == MenuPage.Music -> music
            canStart -> start
            else -> duration
        }.requestFocusSafely()
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    )
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = modifier,
    )
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            Icon(
                if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        modifier = modifier,
    )
}

/**
 * Shown for a few seconds when the slideshow starts or resumes and when a track starts: the
 * duration, the track and its artist, the keys, and a bar counting down to the next photo.
 */
@Composable
fun SlideshowStatus(seconds: Int, track: Track?, progress: Float) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .background(Color(0xE0202124), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("Slideshow · $seconds s por foto", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            if (track != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                    Text(
                        "${track.title} · ${track.artist}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeyHints("OK" to "pausa", "Voltar" to "termina", modifier = Modifier.padding(top = 40.dp, bottom = 14.dp))
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                drawRect(Color.White.copy(alpha = 0.2f))
                drawRect(Color.White, size = size.copy(width = size.width * progress.coerceIn(0f, 1f)), topLeft = Offset.Zero)
            }
        }
    }
}

/** While paused: a pause sign in the middle and the keys that work now. */
@Composable
fun SlideshowPaused(track: Track?) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
        Box(Modifier.align(Alignment.Center)) {
            CenterDisc {
                Icon(Icons.Filled.Pause, contentDescription = "Slideshow em pausa", tint = OnControlColor, modifier = Modifier.size(56.dp))
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(top = 56.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Slideshow em pausa", style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (track != null) {
                Text("${track.title} · ${track.artist}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            }
            KeyHints("OK" to "continua", "◀ ▶" to "foto anterior / seguinte", "OK premido" to "opções")
        }
    }
}

@Composable
private fun KeyHints(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hints.forEachIndexed { i, (key, action) ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            Text(
                key,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(action, style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
    }
}
