package com.anmi.devworkspace.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "DevWorkspaceSettings",
    storages = [Storage("dev-workspace.xml")],
)
class DevWorkspaceSettingsState : PersistentStateComponent<DevWorkspaceSettingsState.State> {
    data class State(
        var tasksEnabled: Boolean = true,
        var libraryEnabled: Boolean = true,
        var markmapEnabled: Boolean = true,
        var serverMonitorEnabled: Boolean = true,
    )

    @Volatile
    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    companion object {
        fun getInstance(): DevWorkspaceSettingsState =
            ApplicationManager.getApplication().getService(DevWorkspaceSettingsState::class.java)
    }
}
