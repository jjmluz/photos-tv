package com.jjmluz.photostv.data

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val TAG = "PhotosTV"
private const val FADE_MS = 1_500L
private const val FADE_STEPS = 20

/**
 * Plays a shuffled Jamendo playlist behind a slideshow, fading in and out. It takes audio focus
 * while it plays, so music from another app pauses; with the music off nothing here touches audio,
 * and the other app carries on. Call from the main thread, with a [scope] on it.
 */
class BackgroundMusic(
    private val context: Context,
    private val http: OkHttpClient,
    private val jamendo: Jamendo,
    private val scope: CoroutineScope,
) {
    private var player: ExoPlayer? = null
    private var mood: SlideshowMusic? = null
    private var wanted = false
    private var load: Job? = null
    private var fade: Job? = null

    /** The track playing, to credit its artist. */
    var track by mutableStateOf<Track?>(null)
        private set

    /** Plays [music] (or stops for [SlideshowMusic.Off]), carrying on where it was if it's the same. */
    fun play(music: SlideshowMusic) {
        val tag = music.tag
        if (tag == null || !jamendo.available) return stop()
        wanted = true
        if (music == mood) return resume()
        stop()
        wanted = true
        mood = music
        load = scope.launch {
            val tracks = try {
                jamendo.tracks(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Music: ${e.message}")
                Toast.makeText(context, "Não foi possível carregar a música", Toast.LENGTH_LONG).show()
                mood = null
                return@launch
            }
            Log.i(TAG, "Music: ${tracks.size} tracks for $tag")
            if (tracks.isEmpty()) return@launch
            player = newPlayer(tracks)
            if (wanted) resume()
        }
    }

    fun pause() {
        wanted = false
        val p = player ?: return
        fadeTo(p, 0f) { p.pause() }
    }

    /** Fades out and releases the player. */
    fun stop() {
        wanted = false
        load?.cancel()
        fade?.cancel()
        mood = null
        track = null
        val p = player ?: return
        player = null
        scope.launch {
            try {
                ramp(p, 0f)
            } finally {
                p.release()
            }
        }
    }

    /** Stops at once, when the viewer closes. */
    fun release() {
        load?.cancel()
        fade?.cancel()
        player?.release()
        player = null
    }

    private fun resume() {
        val p = player ?: return
        p.play()
        fadeTo(p, 1f)
    }

    private fun fadeTo(p: ExoPlayer, target: Float, then: () -> Unit = {}) {
        fade?.cancel()
        fade = scope.launch {
            ramp(p, target)
            then()
        }
    }

    private suspend fun ramp(p: ExoPlayer, target: Float) {
        val from = p.volume
        for (i in 1..FADE_STEPS) {
            delay(FADE_MS / FADE_STEPS)
            p.volume = from + (target - from) * i / FADE_STEPS
        }
    }

    private fun newPlayer(tracks: List<Track>): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(http)))
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .build()
        // Skip tracks that fail to load, but give up once a whole round of them has failed.
        var failures = 0
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: ExoMediaItem?, reason: Int) {
                track = mediaItem?.localConfiguration?.tag as? Track
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) failures = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Music: track failed: ${error.errorCodeName}")
                if (++failures >= tracks.size) return
                p.seekToNextMediaItem()
                p.prepare()
            }
        })
        p.setMediaItems(tracks.map { ExoMediaItem.Builder().setUri(it.audioUrl).setTag(it).build() })
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.volume = 0f
        p.prepare()
        return p
    }
}
