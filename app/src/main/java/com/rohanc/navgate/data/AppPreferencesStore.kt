package com.rohanc.navgate.data

import com.rohanc.navgate.ui.state.CityMode

interface AppPreferencesStore {
    suspend fun frontendPreferences(): FrontendPreferences = FrontendPreferences()
    suspend fun setFrontendPreferences(value: FrontendPreferences) {}
    suspend fun isOnboardingSeen(): Boolean

    suspend fun markOnboardingSeen()

    suspend fun cityMode(): CityMode

    suspend fun setCityMode(mode: CityMode)

    suspend fun kiitBetaAccess(): Boolean

    suspend fun setKiitBetaAccess(enabled: Boolean)
}



class InMemoryAppPreferencesStore(
    private var onboardingSeen: Boolean = false,
    private var storedCityMode: CityMode = CityMode.PanIndia,
    private var storedKiitBetaAccess: Boolean = false,
    initialFrontend: FrontendPreferences = FrontendPreferences(),
) : AppPreferencesStore {
    private var frontend = initialFrontend
    override suspend fun frontendPreferences() = frontend
    override suspend fun setFrontendPreferences(value: FrontendPreferences) { frontend = value }
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
