package com.githubbasedengineering.localbridge

import java.io.File
import kotlinx.serialization.json.Json

class StateStorage(
    private val appDataDir: File,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val stateFile = File(appDataDir, "state.json")

    fun load(): PersistedState {
        if (!stateFile.exists()) return PersistedState()
        return runCatching {
            json.decodeFromString<PersistedState>(stateFile.readText())
        }.getOrElse {
            PersistedState()
        }
    }

    fun save(state: PersistedState) {
        appDataDir.mkdirs()
        stateFile.writeText(json.encodeToString(PersistedState.serializer(), state))
    }
}
