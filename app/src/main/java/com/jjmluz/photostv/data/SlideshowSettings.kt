package com.jjmluz.photostv.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jjmluz.photostv.R

/** Background music for slideshows: off, or a Jamendo playlist of instrumental tracks with [tag]. */
enum class SlideshowMusic(@StringRes val label: Int, @StringRes val detail: Int, val tag: String?) {
    Off(R.string.music_off, R.string.music_off_detail, null),
    Calm(R.string.music_calm, R.string.music_calm_detail, "relaxation"),
    Happy(R.string.music_happy, R.string.music_happy_detail, "happy"),
}

/** Static shows one photo after another; dynamic dissolves between them and slowly zooms each one. */
enum class SlideshowMode(@StringRes val label: Int, @StringRes val detail: Int) {
    Static(R.string.mode_static, R.string.mode_static_detail),
    Dynamic(R.string.mode_dynamic, R.string.mode_dynamic_detail),
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
