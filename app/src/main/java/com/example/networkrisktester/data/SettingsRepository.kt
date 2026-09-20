package com.example.networkrisktester.data

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("source_settings", Context.MODE_PRIVATE)

    fun load(): SourceConfig = SourceConfig(
        ipv6Enabled = prefs.getBoolean("ipv6Enabled", true),
        downloadTestEnabled = prefs.getBoolean("downloadTestEnabled", true),
        stabilityTestEnabled = prefs.getBoolean("stabilityTestEnabled", true)
    )

    fun save(config: SourceConfig) {
        prefs.edit()
            .putBoolean("ipv6Enabled", config.ipv6Enabled)
            .putBoolean("downloadTestEnabled", config.downloadTestEnabled)
            .putBoolean("stabilityTestEnabled", config.stabilityTestEnabled)
            .apply()
    }
}
