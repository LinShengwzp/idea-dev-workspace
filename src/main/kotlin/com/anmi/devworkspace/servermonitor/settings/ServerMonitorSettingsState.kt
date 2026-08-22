package com.anmi.devworkspace.servermonitor.settings

import com.anmi.devworkspace.servermonitor.domain.ServerConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Service(Service.Level.APP)
@State(
    name = "DevWorkspaceServerMonitorSettings",
    storages = [Storage("dev-workspace.xml")],
)
class ServerMonitorSettingsState : PersistentStateComponent<ServerMonitorSettingsState.State> {

    data class State(
        val serversJson: String = Json.encodeToString(emptyList<ServerConfig>()),
    ) {
        val servers: List<ServerConfig>
            get() = Json.decodeFromString(serversJson)
    }

    @Volatile
    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    fun getServers(): List<ServerConfig> = currentState.servers

    fun setServers(servers: List<ServerConfig>) {
        currentState = State(serversJson = Json.encodeToString(servers))
    }

    companion object {
        fun getInstance(): ServerMonitorSettingsState =
            ApplicationManager.getApplication().getService(ServerMonitorSettingsState::class.java)
    }
}
