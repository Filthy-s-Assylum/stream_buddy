package com.streambuddy.app.stream

import java.io.IOException

/** Transient failure while fetching playlist or segment data. Worth retrying later. */
class PreCacheNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The stream played fine but cannot be pre-cached (DASH, unknown container, playlist edge cases). */
class UnsupportedStreamException(message: String) : Exception(message)

/** DRM protected (FairPlay/Widevine/PlayReady/CENC): segments cannot be stored for playback. */
class DrmProtectedException(
    val scheme: String,
    val description: String,
) : Exception("DRM protected ($description)")
