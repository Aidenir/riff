package com.aidenir.riff.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState

/**
 * A page with a top bar: back arrow when the page was pushed, a title and
 * actions. [topBar] replaces the bar, e.g. while selecting tracks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    app: AppState,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (topBar != null) {
            topBar()
        } else {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (app.navigator.stack.size > 1) {
                        IconButton(onClick = { app.navigator.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = actions,
            )
        }
        Box(Modifier.weight(1f)) { Column(Modifier.fillMaxSize(), content = content) }
    }
}
