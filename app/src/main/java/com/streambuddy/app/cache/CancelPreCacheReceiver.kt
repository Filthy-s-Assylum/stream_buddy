package com.streambuddy.app.cache

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Cancel" action on a pre-cache notification.
 *
 * The receiver is not exported, so the only way to trigger it is the [android.app.PendingIntent]
 * attached to the notification.
 */
class CancelPreCacheReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val streamId = intent.getLongExtra(EXTRA_STREAM_ID, NO_STREAM_ID)
        if (streamId == NO_STREAM_ID) return

        PreCacheWorker.cancel(context, streamId)

        // The worker's own cancellation path updates Room; the notification is ours to clear.
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(PreCacheWorker.notificationId(streamId))
    }

    companion object {
        const val ACTION_CANCEL = "com.streambuddy.app.action.CANCEL_PRECACHE"
        const val EXTRA_STREAM_ID = "stream_id"
        private const val NO_STREAM_ID = -1L

        /** Builds the intent the notification action fires, carrying the stream to cancel. */
        fun intent(context: Context, streamId: Long): Intent =
            Intent(context, CancelPreCacheReceiver::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_STREAM_ID, streamId)
    }
}
