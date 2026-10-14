package com.krstech.dockers.engine.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.krstech.dockers.engine.local.LocalEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "engine_profiles")

/** Persists engine connection profiles (JSON in DataStore). */
class ProfileRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val KEY_PROFILES = stringPreferencesKey("profiles")
    private val KEY_ACTIVE = stringPreferencesKey("active_profile")

    val profiles: Flow<List<EngineProfile>> = context.profileStore.data.map { prefs ->
        val stored = prefs[KEY_PROFILES]?.let { runCatching { json.decodeFromString(ListSerializer(EngineProfile.serializer()), it) }.getOrNull() }
        stored ?: defaults()
    }

    val activeId: Flow<String?> = context.profileStore.data.map { it[KEY_ACTIVE] }

    suspend fun current(): List<EngineProfile> = profiles.first()

    suspend fun upsert(profile: EngineProfile) {
        val list = current().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        save(list)
    }

    suspend fun delete(id: String) {
        save(current().filterNot { it.id == id })
        if (activeId.first() == id) context.profileStore.edit { it.remove(KEY_ACTIVE) }
    }

    suspend fun setActive(id: String?) {
        context.profileStore.edit { if (id == null) it.remove(KEY_ACTIVE) else it[KEY_ACTIVE] = id }
    }

    private suspend fun save(list: List<EngineProfile>) {
        context.profileStore.edit { it[KEY_PROFILES] = json.encodeToString(ListSerializer(EngineProfile.serializer()), list) }
    }

    private fun defaults(): List<EngineProfile> = listOf(
        EngineProfile(
            id = LOCAL_PROFILE_ID,
            name = "This device",
            type = EngineType.LOCAL,
            url = "tcp://127.0.0.1:${LocalEngine.DEFAULT_PORT}",
            hostSocketPath = LocalEngine.socketPath(context),
            autoConnect = false,
        ),
        EngineProfile(
            id = "wsl-default",
            name = "Docker Desktop (WSL2)",
            type = EngineType.WSL,
            url = "tcp://192.168.1.100:2375",
        ),
    )

    companion object {
        const val LOCAL_PROFILE_ID = "local-engine"
    }
}
