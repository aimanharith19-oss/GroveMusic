package exh.source

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
class ExhPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val logLevel: Preference<Int> = preferenceStore.getInt("eh_log_level", 0)
}
