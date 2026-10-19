package com.rohanc.navgate.data.local

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.rohanc.navgate.data.AppPreferencesStore
import com.rohanc.navgate.data.FrontendPreferences
import com.rohanc.navgate.ui.state.CityMode
import kotlinx.coroutines.flow.first

private val Context.navGatePreferences by preferencesDataStore(
    name = "navgate_settings",
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context,"navgate_app_preferences")) },
)

class DataStoreAppPreferencesStore(context: Context) : AppPreferencesStore {
    private val data = context.applicationContext.navGatePreferences
    override suspend fun frontendPreferences(): FrontendPreferences {
        val p = data.data.first()
        return FrontendPreferences(p[APPEARANCE] ?: "dark",p[SOLID] ?: false,p[MOTION] ?: false,p[VOICE] ?: true)
    }
    override suspend fun setFrontendPreferences(value: FrontendPreferences) { data.edit {
        it[APPEARANCE]=value.appearance;it[SOLID]=value.reduceTransparency;it[MOTION]=value.reduceMotion;it[VOICE]=value.voiceEnabled
    } }
    override suspend fun isOnboardingSeen(): Boolean = data.data.first()[ONBOARDING] ?: false
    override suspend fun markOnboardingSeen() { data.edit { it[ONBOARDING] = true } }
    override suspend fun cityMode(): CityMode = data.data.first()[CITY]?.let { runCatching { CityMode.valueOf(it) }.getOrNull() } ?: CityMode.KiitBeta
    override suspend fun setCityMode(mode: CityMode) { data.edit { it[CITY] = mode.name } }
    override suspend fun kiitBetaAccess(): Boolean = data.data.first()[BETA] ?: false
    override suspend fun setKiitBetaAccess(enabled: Boolean) { data.edit { it[BETA] = enabled } }

    companion object {
        private val APPEARANCE=stringPreferencesKey("frontend_appearance")
        private val SOLID=booleanPreferencesKey("frontend_reduce_transparency")
        private val MOTION=booleanPreferencesKey("frontend_reduce_motion")
        private val VOICE=booleanPreferencesKey("frontend_voice")
        private val ONBOARDING = booleanPreferencesKey("onboarding_seen")
        private val CITY = stringPreferencesKey("city_mode")
        private val BETA = booleanPreferencesKey("kiit_beta_access")
    }
}
