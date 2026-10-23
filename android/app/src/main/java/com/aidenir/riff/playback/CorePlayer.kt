package com.aidenir.riff.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Playback
import com.aidenir.riff.ffi.QueueSnapshot
import com.aidenir.riff.ffi.Repeat
import com.aidenir.riff.ffi.TrackInfo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * A Media3 player facade over the Rust core. librespot produces the audio
 * itself; this only mirrors [Playback] into the media session (notification,
 * lock screen, Bluetooth) and forwards their commands to the core.
 *
 * Its state comes from the flows passed in, never from a static, so the
 * session always shows what the core reported.
 */
@OptIn(UnstableApi::class)
class CorePlayer(looper: Looper, private val state: AppState) : SimpleBasePlayer(looper) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playback: Playback = state.playback.value
    private var queue: QueueSnapshot = state.queue.value
    private var artworkFor: String? = null
    private var artwork: ByteArray? = null

    init {
        scope.launch {
            combine(state.playback, state.queue) { playback, queue -> playback to queue }
                .collect { (playback, queue) ->
                    this@CorePlayer.playback = playback
                    this@CorePlayer.queue = queue
                    invalidateState()
                }
        }
        scope.launch {
            state.playback.distinctUntilChangedBy { it.snapshot.current?.id }.collect { playback ->
                val track = playback.snapshot.current ?: return@collect
                val url = track.art.large ?: track.art.medium ?: return@collect
                val bytes = state.images.bytes(url, ARTWORK_SIZE)
                if (playback.snapshot.current?.id == this@CorePlayer.playback.snapshot.current?.id) {
                    artworkFor = track.id
                    artwork = bytes
                    invalidateState()
                }
            }
        }
    }

    private fun item(track: TrackInfo, uid: String, withArtwork: Boolean): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(track.album?.name)
            .setDurationMs(track.durationMs.toLong())
            .setIsPlayable(true)
            .setIsBrowsable(false)
        if (withArtwork && artworkFor == track.id) {
            artwork?.let { metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        }
        val mediaItem = MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).build()
        return MediaItemData.Builder(uid)
            .setMediaItem(mediaItem)
            .setDurationUs(track.durationMs.toLong() * 1000)
            .setIsSeekable(true)
            .build()
    }

    override fun getState(): State {
        val snapshot = playback.snapshot
        val current = snapshot.current
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_METADATA,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(snapshot.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(snapshot.shuffle)
            .setRepeatMode(
                when (snapshot.repeat) {
                    Repeat.OFF -> REPEAT_MODE_OFF
                    Repeat.TRACK -> REPEAT_MODE_ONE
                    Repeat.CONTEXT -> REPEAT_MODE_ALL
                },
            )
        if (current == null) {
            return builder.setPlaybackState(STATE_IDLE).build()
        }
        // The next entry makes "next" available in the notification; the
        // core decides what really plays next.
        val items = mutableListOf(item(current, "current:${snapshot.currentKey ?: current.id}", true))
        if (snapshot.hasNext) {
            val next = queue.queued.firstOrNull() ?: queue.context.firstOrNull()
            if (next != null) items += item(next.track, "next:${next.key}", false)
        }
        val position = playback.position()
        return builder
            .setPlaybackState(STATE_READY)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                PositionSupplier.getExtrapolating(position, if (snapshot.isPlaying) 1f else 0f),
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) state.core.play() else state.core.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        state.core.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> state.core.next()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> state.core.previous()
            else -> if (positionMs != C.TIME_UNSET) state.core.seek(positionMs.coerceAtLeast(0).toUInt())
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        state.core.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        state.core.setRepeat(
            when (repeatMode) {
                REPEAT_MODE_ONE -> Repeat.TRACK
                REPEAT_MODE_ALL -> Repeat.CONTEXT
                else -> Repeat.OFF
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val ARTWORK_SIZE = 512
    }
}
