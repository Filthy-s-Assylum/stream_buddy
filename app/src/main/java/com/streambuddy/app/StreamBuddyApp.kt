package com.streambuddy.app

import android.app.Application
import com.streambuddy.app.core.security.AppCrypto

class StreamBuddyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The Keystore key must exist before Room or SecurePreferences are
        // touched; otherwise encrypted settings and library rows cannot be
        // read back after a cold start.
        AppCrypto.init(this)
        AppGraph.init(this)
    }
}
