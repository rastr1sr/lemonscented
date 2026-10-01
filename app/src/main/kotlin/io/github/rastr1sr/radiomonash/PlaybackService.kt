package io.github.rastr1sr.radiomonash

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object Sleep {
    private val deadline = MutableStateFlow(0L)
    val until: StateFlow<Long> = deadline
    var stop: (() -> Unit)? = null
    private val scope = MainScope()
    private var timer: Job? = null

    fun set(ms: Long) {
        timer?.cancel()
        deadline.value = if (ms > 0) System.currentTimeMillis() + ms else 0
        if (ms <= 0) return
        timer = scope.launch {
            delay(ms)
            Logs.add("Sleep", "Timer stopped playback")
            deadline.value = 0
            stop?.invoke()
        }
    }
}

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private var lookup: Job? = null
    private var since = 0L
    private val scope = MainScope()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val radio = radio()
        val prefs = radio.preferences.look.value
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(Spectrum)))
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(prefs.noisy)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        prefs.buffer.ms,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    .build(),
            )
            .build()
        player.setMediaItem(MediaItem.fromUri(prefs.stream ?: STREAM))
        Logs.add("Playback", "Buffer ${prefs.buffer.ms} ms, stream ${prefs.stream ?: STREAM}")
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> "Playing"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Stopped"
                }
                Logs.add("Playback", name)
            }

            override fun onPlayerError(error: PlaybackException) {
                Logs.add("Playback", "${error.errorCodeName}: ${error.message}")
            }

            override fun onMetadata(metadata: Metadata) {
                val icy = (0 until metadata.length())
                    .map(metadata::get)
                    .firstNotNullOfOrNull { (it as? IcyInfo)?.title }
                    ?: return
                Logs.add("Stream", icy)
                refresh(player, icy)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) since = SystemClock.elapsedRealtime() else flush()
            }
        })
        refresh(player, null)
        scope.launch {
            radio.preferences.look.drop(1).collect { next ->
                player.setHandleAudioBecomingNoisy(next.noisy)
                val url = next.stream ?: STREAM
                if (player.currentMediaItem?.localConfiguration?.uri?.toString() == url) {
                    return@collect
                }
                val resume = player.playWhenReady && player.playbackState != Player.STATE_IDLE
                player.setMediaItem(MediaItem.fromUri(url))
                Logs.add("Playback", "Stream changed to $url")
                refresh(player, null)
                if (resume) player.prepare()
            }
        }
        val live = object : ForwardingPlayer(player) {
            override fun pause() {
                stop()
                seekToDefaultPosition()
            }
        }
        session = MediaSession.Builder(this, live).build()
        Sleep.stop = live::pause
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        Sleep.stop = null
        Sleep.set(0)
        flush()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun flush() {
        if (since == 0L) return
        radio().library.addListening((SystemClock.elapsedRealtime() - since) / 1000)
        since = 0L
    }

    private fun refresh(player: Player, icy: String?) {
        lookup?.cancel()
        lookup = scope.launch {
            val info = withContext(Dispatchers.IO) { nowPlaying(icy) } ?: return@launch
            if (icy != null && player.isPlaying) {
                radio().library.logPlay(info.artist?.toString(), info.station?.toString())
            }
            val item = player.getMediaItemAt(0)
            player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(info).build())
        }
    }

    private fun nowPlaying(icy: String?): MediaMetadata? = logged("Network", "Now playing") {
        val result = JSONObject(radiocult("schedule/live")).getJSONObject("result")
        val content = result.optJSONObject("content")
        val track = result.optJSONObject("metadata")
            ?.takeIf { icy == null || sameTrack(icy, it.str("title")) }
        MediaMetadata.Builder()
            .setStation(content?.run { str("title") ?: str("name") })
            .setTitle(track?.str("title"))
            .setArtist(track?.str("artist"))
            .setArtworkUri(
                track?.optJSONObject("artwork")?.str("512x512")?.takeIf(::isHttps)?.toUri(),
            )
            .setExtras(Bundle().apply { putString("mode", airMode(result).name) })
            .build()
    }
}
