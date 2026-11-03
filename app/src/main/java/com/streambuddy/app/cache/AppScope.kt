package com.streambuddy.app.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for work that must outlive a view model.
 *
 * Recording a watch session happens while the player is being torn down, which is exactly when
 * `viewModelScope` is already cancelled, so that write gets its own scope instead of being lost.
 */
object AppScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
