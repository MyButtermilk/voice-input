package org.futo.voiceinput.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.voiceinput.MULTILINGUAL_MODELS
import org.futo.voiceinput.R
import org.futo.voiceinput.settings.ALLOW_UNDERTRAINED_LANGUAGES
import org.futo.voiceinput.settings.BEAM_SEARCH
import org.futo.voiceinput.settings.DISALLOW_SYMBOLS
import org.futo.voiceinput.settings.DevOnlySettings
import org.futo.voiceinput.settings.ENABLE_30S_LIMIT
import org.futo.voiceinput.settings.MULTILINGUAL_MODEL_INDEX
import org.futo.voiceinput.settings.NavigationItem
import org.futo.voiceinput.settings.NavigationItemStyle
import org.futo.voiceinput.settings.ScreenTitle
import org.futo.voiceinput.settings.ScrollableList
import org.futo.voiceinput.settings.SettingToggleDataStore
import org.futo.voiceinput.settings.SettingsViewModel
import org.futo.voiceinput.settings.VERBOSE_PROGRESS
import org.futo.voiceinput.settings.openImeOptions
import org.futo.voiceinput.settings.useDataStore
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import org.futo.voiceinput.settings.VAD_SPEECH_MS
import org.futo.voiceinput.settings.VAD_SILENCE_MS
import org.futo.voiceinput.settings.VAD_END_SOON_MS
import org.futo.voiceinput.settings.VAD_FINALIZE_MS

@Composable
@Preview
fun AdvancedScreen(
    settingsViewModel: SettingsViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val (_, setMultilingualIdx) = useDataStore(
        key = MULTILINGUAL_MODEL_INDEX.key,
        default = MULTILINGUAL_MODEL_INDEX.default
    )

    ScrollableList {
        ScreenTitle(title = stringResource(id = R.string.advanced_settings), showBack = true, navController = navController)

        SettingToggleDataStore(
            stringResource(R.string.suppress_non_speech_annotations),
            DISALLOW_SYMBOLS,
            subtitle = stringResource(R.string.suppress_non_speech_annotations_subtitle)
        )

        SettingToggleDataStore(
            stringResource(R.string.verbose_mode),
            VERBOSE_PROGRESS
        )

        SettingToggleDataStore(stringResource(R.string.use_beam_search), BEAM_SEARCH, subtitle = stringResource(R.string.recommended))

        SettingToggleDataStore(
            stringResource(R.string.allow_undertrained_languages),
            ALLOW_UNDERTRAINED_LANGUAGES,
            subtitle = stringResource(R.string.allow_undertrained_languages_subtitle),
            onChanged = {
                // Automatically change model to largest one
                if(it) {
                    setMultilingualIdx(MULTILINGUAL_MODELS.size - 1)
                }
            }
        )

        NavigationItem(
            title = stringResource(R.string.open_input_method_settings),
            style = NavigationItemStyle.Misc,
            navigate = { openImeOptions(context) }
        )

        SettingToggleDataStore(
            stringResource(R.string.re_enable_30s_limit),
            ENABLE_30S_LIMIT,
        )

        Spacer(modifier = Modifier.height(16.dp))
        // VAD thresholds
        val speech = useDataStore(VAD_SPEECH_MS)
        val silence = useDataStore(VAD_SILENCE_MS)
        val endSoon = useDataStore(VAD_END_SOON_MS)
        val finalize = useDataStore(VAD_FINALIZE_MS)

        VADNumberField(
            label = stringResource(R.string.vad_speech_ms_label),
            initial = speech.value.toString()
        ) { v -> v.toIntOrNull()?.let { speech.setValue(it) } }
        VADNumberField(
            label = stringResource(R.string.vad_silence_ms_label),
            initial = silence.value.toString()
        ) { v -> v.toIntOrNull()?.let { silence.setValue(it) } }
        VADNumberField(
            label = stringResource(R.string.vad_end_soon_ms_label),
            initial = endSoon.value.toString()
        ) { v -> v.toIntOrNull()?.let { endSoon.setValue(it) } }
        VADNumberField(
            label = stringResource(R.string.vad_finalize_ms_label),
            initial = finalize.value.toString()
        ) { v -> v.toIntOrNull()?.let { finalize.setValue(it) } }

        DevOnlySettings()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VADNumberField(label: String, initial: String, onValidChange: (String) -> Unit) {
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(state.value) { onValidChange(state.value) }
    TextField(
        value = state.value,
        onValueChange = { state.value = it.filter { ch -> ch.isDigit() } },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    Spacer(modifier = Modifier.height(8.dp))
}
