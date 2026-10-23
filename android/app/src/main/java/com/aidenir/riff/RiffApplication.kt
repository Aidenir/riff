package com.aidenir.riff

import android.app.Application
import com.aidenir.riff.core.KeystoreSecretStore
import com.aidenir.riff.core.NativeLib
import com.aidenir.riff.core.Riff
import com.aidenir.riff.ffi.CoreConfig
import com.aidenir.riff.playback.PlaybackController

/** Creates the Rust core with the app's folders and the Keystore secret store. */
class RiffApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeLib.init(this)
        val state = Riff.create(
            this,
            CoreConfig(supportDir = filesDir.absolutePath, cacheDir = cacheDir.absolutePath),
            KeystoreSecretStore(this),
        )
        PlaybackController.install(this, state)
        state.start()
    }
}
