package com.aidenir.riff.playback

import android.content.Context
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Session
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Runs [LoginService] for as long as the login waits on the browser. */
object LoginKeepAlive {
    fun install(context: Context, state: AppState) {
        val app = context.applicationContext
        state.scope.launch {
            state.session.map { it == Session.WaitingForBrowser }.distinctUntilChanged().collect { waiting ->
                if (waiting) LoginService.start(app) else LoginService.stop(app)
            }
        }
    }
}
