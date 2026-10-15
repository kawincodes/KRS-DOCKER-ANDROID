package com.krstech.dockers.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val logTail: Int = 500,
    val logTimestamps: Boolean = false,
    val confirmDestructive: Boolean = true,
    val showHelperContainers: Boolean = false,
    val localPort: Int = 2375,
    val localStorageDriver: String = "overlay2",
    val localIptables: Boolean = true,
    val localExtraArgs: String = "",
    val terminalFontSize: Int = 13,
    // Docker VM (no-root engine)
    val vmCpus: Int = 0,            // 0 = auto
    val vmMemoryMb: Int = 0,        // 0 = auto
    val vmApiPort: Int = 23750,
    val vmExposePortsToLan: Boolean = false,
    val vmAutoStart: Boolean = false,
    val vmSplitWx: Boolean = false,
    val vmImageUrl: String = "",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val logTail = intPreferencesKey("log_tail")
        val logTs = booleanPreferencesKey("log_ts")
        val confirm = booleanPreferencesKey("confirm_destructive")
        val showHelpers = booleanPreferencesKey("show_helpers")
        val localPort = intPreferencesKey("local_port")
        val localDriver = stringPreferencesKey("local_driver")
        val localIpt = booleanPreferencesKey("local_iptables")
        val localExtra = stringPreferencesKey("local_extra")
        val termFont = intPreferencesKey("term_font")
        val vmCpus = intPreferencesKey("vm_cpus")
        val vmMem = intPreferencesKey("vm_mem")
        val vmPort = intPreferencesKey("vm_port")
        val vmLan = booleanPreferencesKey("vm_lan")
        val vmAuto = booleanPreferencesKey("vm_autostart")
        val vmSplitWx = booleanPreferencesKey("vm_splitwx")
        val vmUrl = stringPreferencesKey("vm_url")
    }

    private fun read(p: Preferences) = AppSettings(
        theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        logTail = p[K.logTail] ?: 500,
        logTimestamps = p[K.logTs] ?: false,
        confirmDestructive = p[K.confirm] ?: true,
        showHelperContainers = p[K.showHelpers] ?: false,
        localPort = p[K.localPort] ?: 2375,
        localStorageDriver = p[K.localDriver] ?: "overlay2",
        localIptables = p[K.localIpt] ?: true,
        localExtraArgs = p[K.localExtra] ?: "",
        terminalFontSize = p[K.termFont] ?: 13,
        vmCpus = p[K.vmCpus] ?: 0,
        vmMemoryMb = p[K.vmMem] ?: 0,
        vmApiPort = p[K.vmPort] ?: 23750,
        vmExposePortsToLan = p[K.vmLan] ?: false,
        vmAutoStart = p[K.vmAuto] ?: false,
        vmSplitWx = p[K.vmSplitWx] ?: false,
        vmImageUrl = p[K.vmUrl] ?: "",
    )

    val settings: Flow<AppSettings> = context.settingsStore.data.map { read(it) }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val cur = read(p)
            val n = block(cur)
            p[K.theme] = n.theme.name; p[K.logTail] = n.logTail; p[K.logTs] = n.logTimestamps; p[K.confirm] = n.confirmDestructive
            p[K.showHelpers] = n.showHelperContainers; p[K.localPort] = n.localPort; p[K.localDriver] = n.localStorageDriver
            p[K.localIpt] = n.localIptables; p[K.localExtra] = n.localExtraArgs; p[K.termFont] = n.terminalFontSize
            p[K.vmCpus] = n.vmCpus; p[K.vmMem] = n.vmMemoryMb; p[K.vmPort] = n.vmApiPort; p[K.vmLan] = n.vmExposePortsToLan
            p[K.vmAuto] = n.vmAutoStart; p[K.vmSplitWx] = n.vmSplitWx; p[K.vmUrl] = n.vmImageUrl
        }
    }
}
