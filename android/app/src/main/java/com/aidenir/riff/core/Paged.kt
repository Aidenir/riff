package com.aidenir.riff.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Offset-paginated loading shared by the library and detail pages. */
class Paged<T>(
    private val scope: CoroutineScope,
    private val pageSize: UInt = 50u,
    private val fetch: suspend (offset: UInt, limit: UInt) -> Pair<List<T>, UInt?>,
) {
    var items by mutableStateOf(emptyList<T>())
        private set
    var total by mutableStateOf<UInt?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    private var lastPageFull = true
    private var job: Job? = null

    val hasMore: Boolean
        get() {
            if (!loaded) return true
            total?.let { return items.size < it.toInt() }
            return lastPageFull
        }

    fun reload() {
        job?.cancel()
        job = scope.launch { reloadNow() }
    }

    suspend fun reloadNow() {
        items = emptyList()
        total = null
        error = null
        loaded = false
        lastPageFull = true
        loadPage()
    }

    fun loadMore() {
        if (job?.isActive == true || !hasMore) return
        job = scope.launch { loadPage() }
    }

    private suspend fun loadPage() {
        try {
            val (page, total) = fetch(items.size.toUInt(), pageSize)
            items = items + page
            this.total = total
            lastPageFull = page.size >= pageSize.toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.describe()
            lastPageFull = false
        }
        loaded = true
    }
}
