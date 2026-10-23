package com.aidenir.riff.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aidenir.riff.BuildConfig
import com.aidenir.riff.R
import com.aidenir.riff.core.AppState
import com.aidenir.riff.ffi.AppSettings
import com.aidenir.riff.ffi.NormalisationKind
import com.aidenir.riff.ffi.NormalisationMode
import com.aidenir.riff.ffi.SampleFormat
import com.aidenir.riff.ffi.StreamBitrate
import com.aidenir.riff.ffi.ThemePreference
import com.aidenir.riff.ffi.VolumeCurve
import com.aidenir.riff.ui.Route
import com.aidenir.riff.ui.components.PageScaffold
import kotlinx.coroutines.launch
import java.util.Locale

/** Every option of the GTK preferences dialog and the Mac Settings window. */
@Composable
fun SettingsScreen(app: AppState) {
    val settings by app.settings.collectAsState()
    val playback by app.playback.collectAsState()
    val update: ((AppSettings) -> AppSettings) -> Unit = app::updateSettings
    PageScaffold(app, title = stringResource(R.string.settings)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Section(stringResource(R.string.general)) }
            item {
                Choice(
                    stringResource(R.string.theme),
                    null,
                    settings.theme,
                    listOf(
                        ThemePreference.SYSTEM to stringResource(R.string.follow_system),
                        ThemePreference.LIGHT to stringResource(R.string.light),
                        ThemePreference.DARK to stringResource(R.string.dark),
                    ),
                ) { value -> update { it.copy(theme = value) } }
            }
            item {
                val locked = playback.snapshot.explicitFilterLocked
                Toggle(
                    stringResource(R.string.skip_explicit),
                    stringResource(if (locked) R.string.skip_explicit_locked else R.string.skip_explicit_description),
                    settings.skipExplicit || locked,
                    enabled = !locked,
                ) { value -> update { it.copy(skipExplicit = value) } }
            }
            item {
                Stepper(
                    stringResource(R.string.shuffle_separation_value, settings.shuffleSeparation.toInt()),
                    stringResource(R.string.shuffle_separation_description),
                    settings.shuffleSeparation.toInt(),
                    1..10,
                ) { value -> update { it.copy(shuffleSeparation = value.toUInt()) } }
            }

            item { Section(stringResource(R.string.audio)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.audio_backend)) },
                    supportingContent = { Text("AAudio") },
                )
            }
            item {
                Choice(
                    stringResource(R.string.audio_quality),
                    stringResource(R.string.audio_quality_description),
                    settings.bitrate,
                    listOf(
                        StreamBitrate.KBPS96 to stringResource(R.string.quality_low),
                        StreamBitrate.KBPS160 to stringResource(R.string.quality_normal),
                        StreamBitrate.KBPS320 to stringResource(R.string.quality_high),
                    ),
                ) { value -> update { it.copy(bitrate = value) } }
            }
            item {
                Choice(
                    stringResource(R.string.audio_format),
                    stringResource(R.string.audio_format_description),
                    settings.audioFormat,
                    listOf(
                        SampleFormat.S16 to stringResource(R.string.format_s16),
                        SampleFormat.S24 to stringResource(R.string.format_s24),
                        SampleFormat.S24X3 to stringResource(R.string.format_s24_3),
                        SampleFormat.S32 to stringResource(R.string.format_s32),
                        SampleFormat.F32 to stringResource(R.string.format_f32),
                        SampleFormat.F64 to stringResource(R.string.format_f64),
                    ),
                ) { value -> update { it.copy(audioFormat = value) } }
            }
            item {
                Toggle(stringResource(R.string.gapless_playback), stringResource(R.string.gapless_description), settings.gaplessPlayback) { value ->
                    update { it.copy(gaplessPlayback = value) }
                }
            }
            item {
                Choice(
                    stringResource(R.string.volume_curve),
                    stringResource(R.string.volume_curve_description),
                    settings.volumeCurve,
                    listOf(
                        VolumeCurve.LOG to stringResource(R.string.curve_log),
                        VolumeCurve.LINEAR to stringResource(R.string.curve_linear),
                        VolumeCurve.CUBIC to stringResource(R.string.curve_cubic),
                    ),
                ) { value -> update { it.copy(volumeCurve = value) } }
            }
            item {
                Toggle(stringResource(R.string.mono_audio), stringResource(R.string.mono_audio_description), settings.monoAudio) { value ->
                    update { it.copy(monoAudio = value) }
                }
            }
            item {
                SliderSetting(
                    stringResource(R.string.stereo_pan),
                    panLabel(settings.pan),
                    settings.pan.toFloat(),
                    -1f..1f,
                    reset = stringResource(R.string.reset),
                ) { value -> update { it.copy(pan = value.toDouble()) } }
            }
            item {
                SliderSetting(
                    stringResource(R.string.pitch_shift),
                    stringResource(R.string.cents, settings.pitchCents.toInt()),
                    settings.pitchCents.toFloat(),
                    -100f..100f,
                    steps = 199,
                    reset = stringResource(R.string.reset),
                ) { value -> update { it.copy(pitchCents = value.toDouble()) } }
            }
            if (settings.featureNormalisation) {
                item { Section(stringResource(R.string.normalisation)) }
                item { NormalisationSettings(settings, update) }
            }

            item { Section(stringResource(R.string.equalizer)) }
            item { Equalizer(settings, update) }

            item { Section(stringResource(R.string.network)) }
            item {
                NumberSetting(
                    stringResource(R.string.ap_port),
                    stringResource(R.string.ap_port_description),
                    settings.apPort.toDouble(),
                    integer = true,
                ) { value -> update { it.copy(apPort = value.toInt().coerceIn(0, 65535).toUInt()) } }
            }

            item { Section(stringResource(R.string.caching)) }
            item {
                Stepper(
                    stringResource(R.string.memory_cache_value, settings.memoryCacheSizeMb.toInt()),
                    stringResource(R.string.memory_cache_description),
                    settings.memoryCacheSizeMb.toInt(),
                    1..1024,
                    step = 8,
                ) { value -> update { it.copy(memoryCacheSizeMb = value.toUInt()) } }
            }
            item {
                Stepper(
                    stringResource(R.string.disk_cache_value, settings.diskCacheSizeMb.toInt()),
                    stringResource(R.string.disk_cache_description),
                    settings.diskCacheSizeMb.toInt(),
                    16..4096,
                    step = 16,
                ) { value -> update { it.copy(diskCacheSizeMb = value.toUInt()) } }
            }
            item {
                Stepper(
                    stringResource(R.string.audio_cache_value, settings.audioCacheSizeMb.toInt()),
                    stringResource(R.string.audio_cache_description),
                    settings.audioCacheSizeMb.toInt(),
                    64..16384,
                    step = 64,
                ) { value -> update { it.copy(audioCacheSizeMb = value.toUInt()) } }
            }
            item { ClearCache(app) }

            item { Section(stringResource(R.string.account)) }
            item {
                val user by app.user.collectAsState()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.log_out)) },
                    supportingContent = user?.let { { Text(it.displayName) } },
                    modifier = Modifier.clickable { app.logout() },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_riff)) },
                    modifier = Modifier.clickable { app.navigator.push(Route.About) },
                )
            }
            if (BuildConfig.DEBUG) {
                item { DebugSettings(app, settings, update) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun <T> Choice(title: String, subtitle: String?, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Column {
                    Text(options.firstOrNull { it.first == value }?.second ?: "", color = MaterialTheme.colorScheme.primary)
                    if (subtitle != null) Text(subtitle)
                }
            },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(open, { open = false }, Modifier.padding(start = 16.dp)) {
            options.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(option == value, null) },
                    onClick = {
                        open = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun Stepper(title: String, subtitle: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row {
                FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) {
                    Icon(Icons.Default.Remove, stringResource(R.string.decrease))
                }
                FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) {
                    Icon(Icons.Default.Add, stringResource(R.string.increase))
                }
            }
        },
    )
}

@Composable
private fun SliderSetting(
    title: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    reset: String,
    onChange: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onChange(0f) }) { Text(reset) }
        }
        Slider(current, { current = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(current) })
    }
}

@Composable
private fun panLabel(pan: Double): String = when {
    pan < -0.01 -> "L ${(-pan * 100).toInt()}"
    pan > 0.01 -> "R ${(pan * 100).toInt()}"
    else -> stringResource(R.string.center)
}

@Composable
private fun NumberSetting(title: String, subtitle: String, value: Double, integer: Boolean = false, onChange: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val shown = if (integer) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text(shown, color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { editing = true },
    )
    if (editing) {
        var text by remember { mutableStateOf(shown) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    text,
                    { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.replace(',', '.').toDoubleOrNull()?.let(onChange)
                    editing = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NormalisationSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Column {
        Toggle(stringResource(R.string.enable_normalisation), stringResource(R.string.normalisation_description), settings.normalisation) { value ->
            update { it.copy(normalisation = value) }
        }
        if (settings.normalisation) {
            Choice(
                stringResource(R.string.normalisation_type),
                stringResource(R.string.normalisation_type_description),
                settings.normalisationType,
                listOf(
                    NormalisationKind.AUTO to stringResource(R.string.automatic),
                    NormalisationKind.TRACK to stringResource(R.string.track),
                    NormalisationKind.ALBUM to stringResource(R.string.album),
                ),
            ) { value -> update { it.copy(normalisationType = value) } }
            Choice(
                stringResource(R.string.normalisation_method),
                stringResource(R.string.normalisation_method_description),
                settings.normalisationMethod,
                listOf(
                    NormalisationMode.DYNAMIC to stringResource(R.string.dynamic),
                    NormalisationMode.BASIC to stringResource(R.string.basic),
                ),
            ) { value -> update { it.copy(normalisationMethod = value) } }
            NumberSetting(stringResource(R.string.pregain), stringResource(R.string.pregain_description), settings.normalisationPregainDb) { v ->
                update { it.copy(normalisationPregainDb = v) }
            }
            NumberSetting(stringResource(R.string.threshold), stringResource(R.string.threshold_description), settings.normalisationThresholdDbfs) { v ->
                update { it.copy(normalisationThresholdDbfs = v) }
            }
            NumberSetting(stringResource(R.string.attack), stringResource(R.string.attack_description), settings.normalisationAttackMs) { v ->
                update { it.copy(normalisationAttackMs = v) }
            }
            NumberSetting(stringResource(R.string.release), stringResource(R.string.release_description), settings.normalisationReleaseMs) { v ->
                update { it.copy(normalisationReleaseMs = v) }
            }
            NumberSetting(stringResource(R.string.knee), stringResource(R.string.knee_description), settings.normalisationKneeDb) { v ->
                update { it.copy(normalisationKneeDb = v) }
            }
        }
    }
}

private val bandLabels = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

private val presets = listOf(
    R.string.eq_flat to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    R.string.eq_bass_boost to listOf(6.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    R.string.eq_treble_boost to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 4.0, 5.0, 6.0),
    R.string.eq_vocal to listOf(-2.0, -2.0, -1.0, 1.0, 3.0, 3.0, 2.0, 1.0, 0.0, -1.0),
    R.string.eq_rock to listOf(4.0, 3.0, 2.0, 0.0, -1.0, -1.0, 1.0, 2.0, 3.0, 4.0),
    R.string.eq_electronic to listOf(5.0, 4.0, 1.0, 0.0, -2.0, 1.0, 0.0, 1.0, 4.0, 5.0),
    R.string.eq_acoustic to listOf(3.0, 3.0, 2.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0),
)

/** The 10-band equalizer, applied live by the audio engine. */
@Composable
private fun Equalizer(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var presetsOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { presetsOpen = true }) { Text(stringResource(R.string.presets)) }
                DropdownMenu(presetsOpen, { presetsOpen = false }) {
                    presets.forEach { (name, bands) ->
                        DropdownMenuItem(text = { Text(stringResource(name)) }, onClick = {
                            presetsOpen = false
                            update { it.copy(eqBands = bands) }
                        })
                    }
                }
            }
            TextButton(onClick = { update { it.copy(eqBands = List(10) { 0.0 }) } }) { Text(stringResource(R.string.reset)) }
        }
        bandLabels.forEachIndexed { band, label ->
            val value = settings.eqBands.getOrElse(band) { 0.0 }
            var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
                Slider(
                    current,
                    { current = it },
                    Modifier.weight(1f),
                    valueRange = -12f..12f,
                    steps = 47,
                    onValueChangeFinished = {
                        update { settings ->
                            val bands = settings.eqBands.toMutableList()
                            while (bands.size < 10) bands += 0.0
                            bands[band] = current.toDouble()
                            settings.copy(eqBands = bands)
                        }
                    },
                )
                Text(String.format(Locale.ROOT, "%+.1f", current), Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ClearCache(app: AppState) {
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    val cleared = stringResource(R.string.cache_cleared)
    ListItem(
        headlineContent = { Text(stringResource(R.string.clear_cache)) },
        supportingContent = { Text(stringResource(R.string.clear_cache_description)) },
        modifier = Modifier.clickable(enabled = !clearing) {
            clearing = true
            scope.launch {
                app.core.clearCache()
                clearing = false
                app.show(cleared)
            }
        },
    )
}

/** Debug builds only, the counterpart of the GTK dev tools and the Mac Debug menu. */
@Composable
private fun DebugSettings(app: AppState, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val scope = rememberCoroutineScope()
    Column {
        Section(stringResource(R.string.debug))
        Toggle(stringResource(R.string.flag_device_selector), null, settings.featureDeviceSelector) { v ->
            update { it.copy(featureDeviceSelector = v) }
        }
        Toggle(stringResource(R.string.flag_normalisation), null, settings.featureNormalisation) { v ->
            update { it.copy(featureNormalisation = v) }
        }
        Toggle(stringResource(R.string.flag_pinned_objects), null, settings.featurePinnedObjects) { v ->
            update { it.copy(featurePinnedObjects = v) }
        }
        ListItem(headlineContent = { Text(stringResource(R.string.restore_session)) }, modifier = Modifier.clickable { app.core.restoreSession() })
        ListItem(headlineContent = { Text(stringResource(R.string.bump_epoch)) }, modifier = Modifier.clickable { app.core.bumpEpoch() })
        ListItem(
            headlineContent = { Text(stringResource(R.string.clear_cache)) },
            modifier = Modifier.clickable { scope.launch { app.core.clearCache() } },
        )
        ListItem(headlineContent = { Text(stringResource(R.string.test_notification)) }, modifier = Modifier.clickable { app.show("Test notification") })
        ListItem(
            headlineContent = { Text(stringResource(R.string.reset_drm)) },
            modifier = Modifier.clickable { app.core.resetDrmVerification() },
        )
    }
}

@Composable
fun AboutScreen(app: AppState) {
    PageScaffold(app, title = stringResource(R.string.about_riff)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher_foreground),
                null,
                Modifier.width(160.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
