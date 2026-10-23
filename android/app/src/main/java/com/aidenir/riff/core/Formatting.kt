package com.aidenir.riff.core

import com.aidenir.riff.ffi.AlbumInfo
import com.aidenir.riff.ffi.CoreException
import com.aidenir.riff.ffi.TrackInfo
import java.util.Locale

/** `3:07`, or `1:02:03` past an hour. */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** `1,234,567` in the user's locale. */
fun formatCount(count: ULong): String = String.format(Locale.getDefault(), "%,d", count.toLong())

/** Total length of a list of tracks, in hours and minutes. */
fun totalMinutes(tracks: List<TrackInfo>): Long = tracks.sumOf { it.durationMs.toLong() } / 60_000

val TrackInfo.artistNames: String get() = artists.joinToString(", ") { it.name }
val AlbumInfo.artistNames: String get() = artists.joinToString(", ") { it.name }

/** A readable message for a failed core call. */
fun Throwable.describe(): String = when (this) {
    is CoreException.Failed -> reason
    is CoreException.NotLoggedIn -> "Not logged in"
    else -> message ?: toString()
}

/** Strips the HTML Spotify puts in descriptions and bios. */
fun stripTags(text: String): String =
    android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
