package com.cwjitsu.app.service

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes as MediaAudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground MediaSessionService that hosts the audio engine and TTS, so playback
 * can continue when the user backgrounds the app. Media3 handles the lock-screen
 * notification controls; the actual audio is generated synthetically by
 * [com.cwjitsu.app.audio.CwAudioEngine] driven by the application's
 * [SessionOrchestrator].
 *
 * The ExoPlayer instance is required by Media3's session contract. We hand it
 * a single empty media item so its state machine is happy; we ignore its actual
 * playback. The orchestrator drives the audio engine directly.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttrs = MediaAudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .build()
        val ep = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
            volume = 0f
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId("cwjitsu-engine-placeholder")
                    .setMediaMetadata(MediaMetadata.EMPTY)
                    .build()
            )
            prepare()
            playWhenReady = false
        }
        player = ep
        session = MediaSession.Builder(this, ep)
            .setCallback(PlaybackCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }
}

/**
 * Minimal MediaSession.Callback so the session accepts controllers. Media-button
 * events from the notification surface come through here.
 *
 * Note: we deliberately do NOT override onPlayerCommand or onPlaybackResumption.
 * Playback is driven by the application's SessionOrchestrator, not by the
 * ExoPlayer instance, so those callbacks are unnecessary.
 */
@UnstableApi
class PlaybackCallback : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        return MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
        )
    }
}
