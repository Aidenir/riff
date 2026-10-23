package com.aidenir.riff.core

import android.content.Context

/** Loads the Rust core and hands it what it needs from the Java side. */
object NativeLib {
    init {
        System.loadLibrary("riff_ffi")
    }

    /** Gives cpal the Java VM and app context it uses for audio output. */
    external fun init(context: Context)
}
