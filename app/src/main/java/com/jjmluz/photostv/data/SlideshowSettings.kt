package com.jjmluz.photostv.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Background music for slideshows: off, or a Jamendo playlist of instrumental tracks with [tag]. */
enum class SlideshowMusic(val label: String, val detail: String, val tag: String?) {
    Off("Desligada", "A música que estiver a tocar noutra app continua", null),
    Calm("Calma", "Instrumental tranquila", "relaxation"),
    Happy("Alegre", "Instrumental animada", "happy"),
}

/** Static shows one photo after another; dynamic dissolves between them and slowly zooms each one. */
enum class SlideshowMode(val label: String, val detail: String) {
    Static("Estático", "Uma foto de cada vez, sem movimento"),
    Dynamic("Dinâmico", "Transições suaves e um zoom lento em cada foto"),
}

/** How long each photo stays on screen, how they change and which music plays, kept between launches. */
class SlideshowSettings(context: Context) {

    private val prefs = context.getSharedPreferences("slideshow", Context.MODE_PRIVATE)

    private var _seconds by mutableIntStateOf(prefs.getInt("seconds", 5))
    var seconds: Int
        get() = _seconds
        set(value) {
            _seconds = value
            prefs.edit().putInt("seconds", value).apply()
        }

    private var _mode by mutableStateOf(
        SlideshowMode.entries.find { it.name == prefs.getString("mode", null) } ?: SlideshowMode.Static,
    )
    var mode: SlideshowMode
        get() = _mode
        set(value) {
            _mode = value
            prefs.edit().putString("mode", value.name).apply()
        }

    private var _music by mutableStateOf(
        SlideshowMusic.entries.find { it.name == prefs.getString("music", null) } ?: SlideshowMusic.Off,
    )
    var music: SlideshowMusic
        get() = _music
        set(value) {
            _music = value
            prefs.edit().putString("music", value.name).apply()
        }

    companion object {
        val Durations = listOf(3, 5, 8, 10, 15, 30)
    }
}
