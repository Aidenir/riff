package com.aidenir.riff.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.stripTags
import com.aidenir.riff.ffi.Artwork
import com.aidenir.riff.ffi.PlaySource
import com.aidenir.riff.ffi.Priority

/**
 * Big artwork, title and actions at the top of album, playlist, artist and
 * profile pages, over a tint from the artwork.
 */
@Composable
fun DetailHeader(
    app: AppState,
    kind: String,
    title: String,
    art: Artwork,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    subtitle: (@Composable () -> Unit)? = null,
    details: List<String> = emptyList(),
    description: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth()) {
        ArtTintBackground(app.images, art.medium ?: art.large, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArtworkImage(
                app.images,
                art.large ?: art.medium,
                Modifier.size(220.dp).shadow(12.dp, if (circle) CircleShape else MaterialTheme.shapes.medium),
                shape = if (circle) CircleShape else MaterialTheme.shapes.medium,
                priority = Priority.HERO,
            )
            Text(
                kind.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.invoke()
            if (!description.isNullOrBlank()) {
                Text(
                    stripTags(description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions() }
        }
    }
}

/** Play and Shuffle buttons for a whole source. */
@Composable
fun PlayButtons(app: AppState, source: PlaySource) {
    Button(onClick = { app.play(source) }) {
        Icon(Icons.Default.PlayArrow, null)
        Text(stringResource(R.string.play), Modifier.padding(start = 4.dp))
    }
    FilledTonalIconButton(onClick = { app.play(source, shuffle = true) }) {
        Icon(Icons.Default.Shuffle, stringResource(R.string.shuffle))
    }
}
