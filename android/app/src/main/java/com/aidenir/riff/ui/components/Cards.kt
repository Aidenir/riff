package com.aidenir.riff.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.CardItem
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.core.artistNames
import com.aidenir.riff.ffi.Priority
import com.aidenir.riff.ui.Route

val CardItem.route: Route
    get() = when (this) {
        is CardItem.Album -> Route.Album(album.id)
        is CardItem.Playlist -> Route.Playlist(playlist.id)
        is CardItem.Artist -> Route.Artist(artist.id)
    }

@Composable
fun cardSubtitle(item: CardItem): String = when (item) {
    is CardItem.Album -> listOfNotNull(item.album.artistNames.ifEmpty { null }, item.album.year?.toString()).joinToString(" · ")
    is CardItem.Playlist -> item.playlist.owner?.name ?: ""
    is CardItem.Artist -> stringResource(R.string.artist)
}

/** Plain-text subtitle for sorting, outside composition. */
fun cardCreator(item: CardItem): String = when (item) {
    is CardItem.Album -> item.album.artistNames
    is CardItem.Playlist -> item.playlist.owner?.name ?: ""
    is CardItem.Artist -> item.artist.name
}

/** Artwork with a title and subtitle. Tap opens it, long press shows actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardView(app: AppState, item: CardItem, modifier: Modifier = Modifier) {
    val circle = item is CardItem.Artist
    Column(
        modifier
            .combinedClickable(
                onClick = { app.navigator.push(item.route) },
                onLongClick = { app.overlay = Overlay.CardActions(item) },
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtworkImage(
            app.images,
            item.art.medium ?: item.art.large,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (circle) CircleShape else MaterialTheme.shapes.medium,
            priority = Priority.OFFSCREEN,
            placeholder = when (item) {
                is CardItem.Album -> Icons.Default.Album
                is CardItem.Playlist -> Icons.AutoMirrored.Filled.QueueMusic
                is CardItem.Artist -> Icons.Default.Person
            },
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cardSubtitle(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A titled, horizontally scrolling row of cards. Hidden when empty. */
@Composable
fun CardShelf(app: AppState, title: String, items: List<CardItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier) {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(items, key = { it.id }) { item ->
                CardView(app, item, Modifier.width(150.dp))
            }
        }
    }
}
