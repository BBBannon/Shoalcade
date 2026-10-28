package uk.benmarks.shoalcade

import android.app.Application

/** Runs before any screen or service, so [Storage] always knows where the app's files are. */
class ShoalcadeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
    }
}
