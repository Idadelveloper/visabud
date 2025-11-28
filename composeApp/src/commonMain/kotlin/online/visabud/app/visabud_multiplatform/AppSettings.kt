package online.visabud.app.visabud_multiplatform

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get

internal object AppSettings {
    private const val KEY_WELCOMED = "key_welcomed"
    private val settings: Settings = Settings()

    var hasBeenWelcomed: Boolean
        get() = settings[KEY_WELCOMED, false]
        set(value) { settings.putBoolean(KEY_WELCOMED, value) }
}
