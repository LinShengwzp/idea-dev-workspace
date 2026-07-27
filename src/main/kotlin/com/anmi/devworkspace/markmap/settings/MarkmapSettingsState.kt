package com.anmi.devworkspace.markmap.settings

import com.anmi.devworkspace.markmap.MarkmapSuffixMatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level MarkMap recognition settings shared by every open project.
 */
@Service(Service.Level.APP)
@State(
    name = "DevWorkspaceMarkmapSettings",
    storages = [Storage("dev-workspace.xml")],
)
class MarkmapSettingsState : PersistentStateComponent<MarkmapSettingsState.State> {
    data class State(
        var suffixes: MutableList<String> =
            MarkmapSuffixMatcher.DEFAULT_SUFFIXES.toMutableList(),
    )

    @Volatile
    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        updateSuffixes(state.suffixes)
    }

    fun suffixes(): List<String> = currentState.suffixes.toList()

    fun updateSuffixes(values: Iterable<String>) {
        currentState = State(
            MarkmapSuffixMatcher.normalizeSuffixes(values).toMutableList(),
        )
    }

    companion object {
        fun getInstance(): MarkmapSettingsState =
            ApplicationManager.getApplication().getService(MarkmapSettingsState::class.java)
    }
}
