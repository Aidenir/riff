package com.aidenir.riff.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import com.aidenir.riff.core.AppState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the system expects of a music player, driven by the core's playback
 * state: audio focus (pause for calls, let the system duck for navigation),
 * pausing when headphones are unplugged, a partial wake lock while playing,
 * and the playback service that keeps the process in the foreground.
 */
class PlaybackController private constructor(private val context: Context, private val state: AppState) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Riff:playback")
        .apply { setReferenceCounted(false) }
    private var hasFocus = false
    private var pausedForFocus = false
    private var noisyRegistered = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        // The system ducks by itself for navigation prompts.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "Headphones unplugged, pausing")
                state.core.pause()
            }
        }
    }

    private fun start() {
        state.scope.launch {
            state.playback
                .map { it.snapshot.isPlaying to (it.snapshot.deviceId == null) }
                .distinctUntilChanged()
                .collect { (playing, local) -> update(playing && local, playing) }
        }
        state.scope.launch {
            state.playback.map { it.snapshot.current != null }.distinctUntilChanged().collect { active ->
                if (active) startService()
            }
        }
    }

    private fun startService() {
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed from the background; the session starts it when needed.
            Log.d(TAG, "Playback service not started: ${e.message}")
        }
    }

    private fun update(playingHere: Boolean, playing: Boolean) {
        if (playingHere) {
            pausedForFocus = false
            if (!hasFocus) {
                hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            if (!noisyRegistered) {
                context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
                noisyRegistered = true
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT)
        } else {
            if (noisyRegistered) {
                context.unregisterReceiver(noisy)
                noisyRegistered = false
            }
            if (wakeLock.isHeld) wakeLock.release()
            // Keep focus through a pause caused by a call, to resume after it.
            if (hasFocus && !pausedForFocus && !playing) {
                audio.abandonAudioFocusRequest(focusRequest)
                hasFocus = false
            }
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pausedForFocus = false
                state.core.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (state.playback.value.snapshot.isPlaying) {
                    pausedForFocus = true
                    state.core.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedForFocus) {
                    pausedForFocus = false
                    state.core.play()
                }
            }
        }
    }

    companion object {
        private const val TAG = "RiffPlayback"
        // Renewed on every play; a safety net if a pause is never reported.
        private const val WAKE_LOCK_TIMEOUT = 6 * 60 * 60 * 1000L

        fun install(context: Context, state: AppState) {
            PlaybackController(context.applicationContext, state).start()
        }
    }
}
