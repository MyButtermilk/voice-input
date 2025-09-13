package org.futo.voiceinput.settings.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.voiceinput.R
import org.futo.voiceinput.settings.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun SpeechProviderScreen(
    settingsViewModel: SettingsViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    val (provider, setProvider) = useDataStore(STT_PROVIDER.key, STT_PROVIDER.default)
    val (apiKey, setApiKey) = useDataStore(SONIOX_API_KEY.key, SONIOX_API_KEY.default)
    val (mode, setMode) = useDataStore(SONIOX_MODE.key, SONIOX_MODE.default)

    ScrollableList {
        ScreenTitle(title = stringResource(R.string.stt_provider), showBack = true, navController = navController)

        SettingRadio(
            title = stringResource(R.string.stt_provider_label),
            options = listOf("whisper_local", "soniox_cloud"),
            optionNames = listOf(
                stringResource(R.string.provider_whisper_local),
                stringResource(R.string.provider_soniox_cloud)
            ),
            setting = STT_PROVIDER
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (provider == "soniox_cloud") {
            SettingRadio(
                title = stringResource(R.string.soniox_mode_label),
                options = listOf("async", "realtime"),
                optionNames = listOf(
                    stringResource(R.string.provider_soniox_async),
                    stringResource(R.string.provider_soniox_realtime)
                ),
                setting = SONIOX_MODE
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = stringResource(R.string.soniox_api_key_label), modifier = Modifier.padding(16.dp, 4.dp))
            val textFieldValue = remember { mutableStateOf(apiKey) }
            LaunchedEffect(textFieldValue.value) { setApiKey(textFieldValue.value) }
            TextField(
                value = textFieldValue.value,
                onValueChange = { textFieldValue.value = it },
                placeholder = { Text(stringResource(R.string.soniox_api_key_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp)
            )
        }
    }
}
