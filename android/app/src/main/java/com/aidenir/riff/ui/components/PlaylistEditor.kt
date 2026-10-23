package com.aidenir.riff.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Overlay
import com.aidenir.riff.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Create a playlist, or edit the name, description, visibility and cover of one. */
@Composable
fun PlaylistEditorDialog(app: AppState, request: Overlay.PlaylistEditor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlist = request.playlist
    var name by remember { mutableStateOf(playlist?.title ?: "") }
    var description by remember { mutableStateOf(playlist?.description ?: "") }
    var public by remember { mutableStateOf(playlist?.public ?: false) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                cover = withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }.getOrNull()
                }
            }
        }
    }

    fun save() {
        saving = true
        val trimmed = name.trim()
        val text = description.ifBlank { null }
        app.scope.launch {
            try {
                val id = if (playlist != null) {
                    app.core.updatePlaylist(playlist.id, trimmed, text, public)
                    playlist.id
                } else {
                    val created = app.core.createPlaylist(trimmed, text, public)
                    if (request.tracksToAdd.isNotEmpty()) {
                        app.core.addToPlaylist(created.id, request.tracksToAdd.map { it.id })
                    }
                    app.navigator.push(Route.Playlist(created.id))
                    created.id
                }
                cover?.let { bitmap ->
                    val jpeg = withContext(Dispatchers.Default) { squareJpeg(bitmap) }
                    if (jpeg != null) app.core.uploadPlaylistCover(id, jpeg)
                }
                app.overlay = null
            } catch (e: Exception) {
                app.report(e)
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) app.overlay = null },
        title = { Text(stringResource(if (playlist == null) R.string.new_playlist else R.string.edit_playlist)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .size(140.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .align(Alignment.CenterHorizontally),
                ) {
                    val picked = cover
                    if (picked != null) {
                        Image(picked.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        ArtworkImage(
                            app.images,
                            playlist?.art?.medium,
                            Modifier.fillMaxSize(),
                            placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                        )
                    }
                    Text(
                        stringResource(R.string.choose_image),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp),
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    minLines = 2,
                    maxLines = 5,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.public_playlist), Modifier.weight(1f))
                    Switch(public, { public = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = name.isNotBlank() && !saving) {
                Text(stringResource(if (playlist == null) R.string.create else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { app.overlay = null }, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Crops to a centred square, scales to at most 640 px and encodes a JPEG
 * small enough for Spotify's 256 KB limit (after base64).
 */
fun squareJpeg(image: Bitmap): ByteArray? {
    val side = minOf(image.width, image.height)
    val square = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
    val size = minOf(side, 640)
    val scaled = Bitmap.createScaledBitmap(square, size, size, true)
    for (quality in listOf(90, 75, 60, 45, 30)) {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() * 4 / 3 <= 256 * 1024) return out.toByteArray()
    }
    return null
}
