package io.github.rastr1sr.radiomonash

import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.IOException
import kotlin.concurrent.thread
import org.json.JSONException

class PlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setMediaItem(MediaItem.fromUri("https://radio-monash.radiocult.fm/stream"))
        player.addListener(object : Player.Listener {
            override fun onMetadata(metadata: Metadata) {
                val icy = (0 until metadata.length())
                    .map(metadata::get)
                    .firstNotNullOfOrNull { (it as? IcyInfo)?.title }
                    ?: return
                thread {
                    val info = nowPlaying(icy) ?: return@thread
                    ContextCompat.getMainExecutor(this@PlaybackService).execute {
                        val item = player.getMediaItemAt(0)
                        player.replaceMediaItem(0, item.buildUpon().setMediaMetadata(info).build())
                    }
                }
            }
        })
        val live = object : ForwardingPlayer(player) {
            override fun pause() = stop()
        }
        session = MediaSession.Builder(this, live).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private fun nowPlaying(icy: String): MediaMetadata? = try {
        val result = radiocult("schedule/live").getJSONObject("result")
        val content = result.optJSONObject("content")
        val track = result.optJSONObject("metadata")
            ?.takeIf { sameTrack(icy, it.str("title")) }
        MediaMetadata.Builder()
            .setStation(content?.run { str("title") ?: str("name") })
            .setTitle(track?.str("title"))
            .setArtist(track?.str("artist"))
            .setArtworkUri(track?.optJSONObject("artwork")?.str("512x512")?.toUri())
            .setExtras(bundleOf("mode" to airMode(result)))
            .build()
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }
}
