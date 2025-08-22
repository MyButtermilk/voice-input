package org.futo.voiceinput.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SttProviderSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun savesSelectedProvider() = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(scope = this) {
            tmp.newFile("settings.preferences_pb")
        }

        dataStore.edit { prefs ->
            prefs[STT_PROVIDER.key] = SttProvider.SONIOX.ordinal
            prefs[SONIOX_API_KEY.key] = "abc"
        }

        val stored = dataStore.data.first()[STT_PROVIDER.key]
        val apiKey = dataStore.data.first()[SONIOX_API_KEY.key]

        assertEquals(SttProvider.SONIOX.ordinal, stored)
        assertEquals("abc", apiKey)
    }
}
