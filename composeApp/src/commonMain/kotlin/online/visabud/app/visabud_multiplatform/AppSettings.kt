package online.visabud.app.visabud_multiplatform

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get

internal object AppSettings {
    private const val KEY_WELCOMED = "key_welcomed"
    private const val KEY_HYBRID_VERIFICATION = "key_hybrid_verification"
    private val settings: Settings = Settings()

    var hasBeenWelcomed: Boolean
        get() = settings[KEY_WELCOMED, false]
        set(value) { settings.putBoolean(KEY_WELCOMED, value) }

    // Optional hybrid verification (cloud/online) when user opts in. Default false for privacy/offline-first.
    var hybridVerificationOptIn: Boolean
        get() = settings[KEY_HYBRID_VERIFICATION, false]
        set(value) { settings.putBoolean(KEY_HYBRID_VERIFICATION, value) }
}
