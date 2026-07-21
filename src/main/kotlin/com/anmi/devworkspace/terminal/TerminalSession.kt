package com.anmi.devworkspace.terminal

import kotlinx.coroutines.flow.Flow

data class TerminalCommandEvent(
    val executionId: String?,
    val state: TerminalCommandState,
    val exitCode: Int? = null,
)

enum class TerminalCommandState { STARTED, FINISHED, OUTPUT_CHANGED, SESSION_CLOSED }

interface TerminalSession {
    val id: String
    val title: String
    val events: Flow<TerminalCommandEvent>

    suspend fun awaitReady(timeoutMillis: Long): Boolean
    suspend fun execute(text: String)
    suspend fun sendInterrupt()
    suspend fun activate()
    suspend fun close()
}
