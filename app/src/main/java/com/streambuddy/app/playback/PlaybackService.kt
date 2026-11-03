package com.streambuddy.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streambuddy.app.AppGraph

/**
 * Media3's session service. It hosts the already-created [MediaSession], which
 * keeps playback alive with a media notification when the activity is
 * backgrounded or the screen is off.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = AppGraph.containerOrNull()?.playerEngine?.session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val engine = AppGraph.containerOrNull()?.playerEngine
        if (engine == null || !engine.player.isPlaying) {
            stopSelf()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }
}
