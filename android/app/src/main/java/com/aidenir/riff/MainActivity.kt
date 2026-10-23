package com.aidenir.riff

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aidenir.riff.core.Riff
import com.aidenir.riff.core.Session
import com.aidenir.riff.debug.SmokeTest
import com.aidenir.riff.ui.RiffApp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val state get() = Riff.state

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RiffApp(state) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.loginUrls.collectLatest { openLogin(it) }
            }
        }
        // Back to the app once the browser finished logging in.
        lifecycleScope.launch {
            state.session.filterIsInstance<Session.LoggedIn>().collectLatest {
                if (lifecycle.currentState < Lifecycle.State.RESUMED) {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
                pendingLink?.let { link ->
                    pendingLink = null
                    state.openShared(link)
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private var pendingLink: String? = null

    /**
     * Hardware keyboard shortcuts, as in the desktop apps: space plays and
     * pauses, Ctrl+arrows seek and change tracks. Text fields get keys first.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (state.session.value !is Session.LoggedIn) return super.onKeyDown(keyCode, event)
        val core = state.core
        when {
            keyCode == KeyEvent.KEYCODE_SPACE && !event.hasModifiers(KeyEvent.META_CTRL_ON) -> core.togglePlay()
            event.isCtrlPressed && event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> core.next()
            event.isCtrlPressed && event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> core.previous()
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> core.seekBy(10_000)
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> core.seekBy(-10_000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (BuildConfig.DEBUG && intent.getStringExtra("smoke") == "1") {
            SmokeTest.enable()
        }
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        if (state.session.value is Session.LoggedIn) {
            if (!state.openShared(link)) state.show(getString(R.string.no_spotify_link))
        } else {
            pendingLink = link
        }
    }

    private fun openLogin(url: String) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(this, Uri.parse(url))
    }
}
