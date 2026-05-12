package com.rohanc.navgate.data

import android.content.Context
import androidx.core.content.edit
import com.rohanc.navgate.ui.state.CityMode

interface AppPreferencesStore {
    suspend fun isOnboardingSeen(): Boolean

    suspend fun markOnboardingSeen()

    suspend fun cityMode(): CityMode

    suspend fun setCityMode(mode: CityMode)

    suspend fun kiitBetaAccess(): Boolean

    suspend fun setKiitBetaAccess(enabled: Boolean)
}

class SharedPrefsAppPreferencesStore(
    context: Context,
) : AppPreferencesStore {
    private val prefs = context.getSharedPreferences("navgate_app_preferences", Context.MODE_PRIVATE)

    override suspend fun isOnboardingSeen(): Boolean = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)

    override suspend fun markOnboardingSeen() {
        prefs.edit { putBoolean(KEY_ONBOARDING_SEEN, true) }
    }

    override suspend fun cityMode(): CityMode =
        runCatching { CityMode.valueOf(prefs.getString(KEY_CITY_MODE, CityMode.Mumbai.name) ?: CityMode.Mumbai.name) }
            .getOrDefault(CityMode.Mumbai)

    override suspend fun setCityMode(mode: CityMode) {
        prefs.edit { putString(KEY_CITY_MODE, mode.name) }
    }

    override suspend fun kiitBetaAccess(): Boolean = prefs.getBoolean(KEY_KIIT_BETA_ACCESS, false)

    override suspend fun setKiitBetaAccess(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KIIT_BETA_ACCESS, enabled) }
    }

    companion object {
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
        private const val KEY_CITY_MODE = "city_mode"
        private const val KEY_KIIT_BETA_ACCESS = "kiit_beta_access"
    }
}

class InMemoryAppPreferencesStore(
    private var onboardingSeen: Boolean = false,
    private var storedCityMode: CityMode = CityMode.Mumbai,
    private var storedKiitBetaAccess: Boolean = false,
) : AppPreferencesStore {
    override suspend fun isOnboardingSeen(): Boolean = onboardingSeen

    override suspend fun markOnboardingSeen() {
        onboardingSeen = true
    }

    override suspend fun cityMode(): CityMode = storedCityMode

    override suspend fun setCityMode(mode: CityMode) {
        storedCityMode = mode
    }

    override suspend fun kiitBetaAccess(): Boolean = storedKiitBetaAccess

    override suspend fun setKiitBetaAccess(enabled: Boolean) {
        storedKiitBetaAccess = enabled
    }
}
