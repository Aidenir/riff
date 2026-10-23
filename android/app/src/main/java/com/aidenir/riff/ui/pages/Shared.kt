package com.aidenir.riff.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.aidenir.riff.core.AppState
import com.aidenir.riff.ui.components.RowContext

/** Playback and like state for track rows, collected once per page. */
@Composable
fun rememberRowContext(app: AppState): RowContext {
    val playback by app.playback.collectAsState()
    val overrides by app.likedOverrides.collectAsState()
    val currentId = playback.snapshot.current?.id
    val playing = playback.snapshot.isPlaying
    return remember(currentId, playing, overrides) {
        RowContext(currentId, playing) { overrides[it.id] ?: it.saved ?: false }
    }
}
