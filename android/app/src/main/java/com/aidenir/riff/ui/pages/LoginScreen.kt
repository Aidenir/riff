package com.aidenir.riff.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.core.Session
import com.aidenir.riff.ui.components.EqualizerLoader

/** Logging in through Spotify's page in a browser tab. */
@Composable
fun LoginScreen(app: AppState) {
    val session by app.session.collectAsState()
    val error by app.loginError.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(160.dp))
        Text(stringResource(R.string.welcome_to_riff), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.login_explanation),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (session) {
            Session.Starting -> EqualizerLoader()
            Session.WaitingForBrowser -> {
                Text(stringResource(R.string.finish_login_in_browser), textAlign = TextAlign.Center)
                EqualizerLoader(height = 28.dp)
                TextButton(onClick = app::login) { Text(stringResource(R.string.open_login_page_again)) }
            }
            else -> Button(onClick = app::login) { Text(stringResource(R.string.log_in_with_spotify)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
