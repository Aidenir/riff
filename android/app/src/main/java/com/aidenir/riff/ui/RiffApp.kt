package com.aidenir.riff.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Session
import com.aidenir.riff.debug.SmokeTest
import com.aidenir.riff.ui.components.BottomDock
import com.aidenir.riff.ui.components.Overlays
import com.aidenir.riff.ui.components.SplashIntro
import com.aidenir.riff.ui.pages.AboutScreen
import com.aidenir.riff.ui.pages.AlbumScreen
import com.aidenir.riff.ui.pages.ArtistScreen
import com.aidenir.riff.ui.pages.FolderScreen
import com.aidenir.riff.ui.pages.HomeScreen
import com.aidenir.riff.ui.pages.LibraryScreen
import com.aidenir.riff.ui.pages.LoginScreen
import com.aidenir.riff.ui.pages.NowPlayingScreen
import com.aidenir.riff.ui.pages.PlaylistScreen
import com.aidenir.riff.ui.pages.SearchScreen
import com.aidenir.riff.ui.pages.SettingsScreen
import com.aidenir.riff.ui.pages.UserScreen

/** The whole app: login, or the tabs with the mini player and Now Playing. */
@Composable
fun RiffApp(app: AppState) {
    val settings by app.settings.collectAsState()
    RiffTheme(isDark(settings.theme)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val session by app.session.collectAsState()
            // The intro plays at least once through, and stays while the
            // stored session is being restored.
            var introDone by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(1600)
                introDone = true
            }
            val showIntro = !introDone || session == Session.Starting
            if (!showIntro) {
                if (session is Session.LoggedIn) {
                    MainScreen(app)
                    LaunchedEffect(Unit) { SmokeTest.startIfEnabled(app) }
                } else {
                    LoginScreen(app)
                }
            }
            AnimatedVisibility(
                visible = showIntro,
                enter = fadeIn(),
                exit = fadeOut(tween(450)) + scaleOut(tween(450), targetScale = 1.25f),
            ) {
                SplashIntro()
            }
            Dialogs(app)
        }
    }
}

@Composable
private fun MainScreen(app: AppState) {
    val navigator = app.navigator
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        app.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }
    val connectionLost by app.connectionLost.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            bottomBar = { BottomDock(app) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                if (connectionLost) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connection_lost), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                PageHost(app)
            }
        }
        AnimatedVisibility(
            visible = navigator.nowPlayingOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(app)
        }
        Overlays(app)
    }
}

/** The current tab's top page, keeping each page's scroll position. */
@Composable
private fun PageHost(app: AppState) {
    val holder = rememberSaveableStateHolder()
    val entry = app.navigator.current
    Crossfade(entry, label = "page") { shown ->
        holder.SaveableStateProvider(shown.key) {
            when (val route = shown.route) {
                Route.Home -> HomeScreen(app, shown)
                Route.Search -> SearchScreen(app, shown)
                Route.Library -> LibraryScreen(app, shown)
                is Route.Album -> AlbumScreen(app, shown, route.id)
                is Route.Artist -> ArtistScreen(app, shown, route.id)
                is Route.Playlist -> PlaylistScreen(app, shown, route.id)
                is Route.User -> UserScreen(app, shown, route.id)
                is Route.Folder -> FolderScreen(app, route)
                Route.Settings -> SettingsScreen(app)
                Route.About -> AboutScreen(app)
            }
        }
    }
}

@Composable
private fun Dialogs(app: AppState) {
    val drm by app.showDrmBlocked.collectAsState()
    val notPremium by app.showNotPremium.collectAsState()
    if (drm) {
        AlertDialog(
            onDismissRequest = { app.showDrmBlocked.value = false },
            title = { Text(stringResource(R.string.drm_title)) },
            text = { Text(stringResource(R.string.drm_description)) },
            confirmButton = { TextButton(onClick = { app.showDrmBlocked.value = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (notPremium) {
        AlertDialog(
            onDismissRequest = { app.showNotPremium.value = false },
            title = { Text(stringResource(R.string.not_premium_title)) },
            text = { Text(stringResource(R.string.not_premium_description)) },
            confirmButton = { TextButton(onClick = { app.showNotPremium.value = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
