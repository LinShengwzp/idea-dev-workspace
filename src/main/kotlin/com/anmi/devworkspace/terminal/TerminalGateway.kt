package com.anmi.devworkspace.terminal

import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask

data class TerminalSessionKey(
    val projectKey: String,
    val taskId: String,
)

interface TerminalGateway {
    suspend fun acquire(
        key: TerminalSessionKey,
        preparedTask: PreparedTask,
        policy: TerminalPolicy,
    ): TerminalSession
}
