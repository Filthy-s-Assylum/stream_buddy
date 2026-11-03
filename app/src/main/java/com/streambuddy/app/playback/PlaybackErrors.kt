package com.streambuddy.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.streambuddy.app.data.StreamKind

/**
 * Turns Media3's numeric error codes into messages a person can act on.
 *
 * The wording deliberately avoids promising that Stream Buddy can defeat
 * DRM, paywalls, geo blocks or expired links. When the cause is outside the
 * app, the message says so instead of pretending a retry will fix it.
 */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    fun friendly(error: PlaybackException, kind: StreamKind): String {
        val wasTorrent = kind == StreamKind.TORRENT
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "The source could not be reached. Check your connection and that the link is still valid."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "The source took too long to answer. The server may be slow or blocking this network."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                httpStatusMessage(error)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                if (wasTorrent) {
                    "The torrent stopped delivering data. No peers are currently seeding the missing pieces."
                } else {
                    "The media file was not found. The link may have expired or the file was moved."
                }
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "Android blocked this unencrypted (http) stream. Use an https source, or allow cleartext media in the network policy."
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                "Stream Buddy does not have permission to read this file. Re-open it from the file picker."
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                "The source returned a byte range that does not exist. The link may be incomplete or the server ignores range requests."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                if (wasTorrent) {
                    "The video data is damaged or incomplete. Try another file in the torrent."
                } else {
                    "The stream is malformed (bad playlist or damaged file)."
                }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                "This container or playlist is not supported. Try MP4, MKV, WebM, HLS (.m3u8) or DASH (.mpd)."
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "This device cannot decode the video's codec or resolution. Try a lower quality source."
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                "The video decoder failed. Retry, or choose a source with an H.264/AAC track."
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                "Android reclaimed the decoder while Stream Buddy was in the background. Press play to continue."
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "The audio output failed. Check that headphones or another app are not holding the audio device."
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
                "This title is DRM protected. Stream Buddy plays media you own or are authorised to stream; it does not bypass DRM."
            PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED ->
                "The source requires a paid account that is not signed in. Stream Buddy cannot unlock it."
            PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION ->
                "The source is not available in this region. Stream Buddy does not bypass geo blocks."
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED ->
                "The source's login expired. Sign in to the provider again and re-open the link."
            PlaybackException.ERROR_CODE_TIMEOUT ->
                "Playback timed out while waiting for data. Try again, or lower the start buffer in Settings."
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "This live stream moved past the playhead. Seek to the live edge and continue."
            PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            PlaybackException.ERROR_CODE_UNSPECIFIED ->
                if (wasTorrent) {
                    "Playback failed. Check that peers are still connected, or try another file in the torrent."
                } else {
                    "Playback failed. Check the link and try again."
                }
            else -> {
                val detail = error.cause?.message?.take(180)
                if (detail.isNullOrBlank()) {
                    "Playback failed (error ${error.errorCode}). Try again or use another source."
                } else {
                    "Playback failed (error ${error.errorCode}): $detail"
                }
            }
        }
    }

    private fun httpStatusMessage(error: PlaybackException): String {
        val status = Regex("(?:Invalid response code|HTTP)\\s*:?\\s*(\\d{3})")
            .find(error.message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (status) {
            401, 403 ->
                "The server refused access (HTTP $status). The link may need a login or has expired."
            404, 410 ->
                "The source no longer exists (HTTP $status). The link is expired or was removed."
            429 ->
                "The server is rate limiting requests (HTTP 429). Wait a minute and retry."
            in 500..599 ->
                "The source server is having problems (HTTP $status). Try again later."
            null -> "The source returned an error and playback cannot continue."
            else -> "The source rejected the request (HTTP $status)."
        }
    }
}
