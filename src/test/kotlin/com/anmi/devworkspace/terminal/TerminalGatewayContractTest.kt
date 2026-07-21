package com.anmi.devworkspace.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalGatewayContractTest {
    @Test
    fun `session distinguishes text execution interrupt and finished event`() = runBlocking {
        val session = FakeTerminalSession()

        session.execute("echo ready")
        session.sendInterrupt()
        session.emit(
            TerminalCommandEvent(
                executionId = "execution-1",
                state = TerminalCommandState.FINISHED,
                exitCode = 7,
            ),
        )

        assertEquals(listOf("execute:echo ready", "interrupt"), session.actions)
        assertEquals(7, session.events.first().exitCode)
    }

    private class FakeTerminalSession : TerminalSession {
        private val eventFlow = MutableSharedFlow<TerminalCommandEvent>(replay = 1)
        val actions = mutableListOf<String>()

        override val id: String = "session-1"
        override val title: String = "Task Terminal"
        override val events: Flow<TerminalCommandEvent> = eventFlow

        override suspend fun awaitReady(timeoutMillis: Long): Boolean = true

        override suspend fun execute(text: String) {
            actions += "execute:$text"
        }

        override suspend fun sendInterrupt() {
            actions += "interrupt"
        }

        override suspend fun activate() = Unit

        override suspend fun close() = Unit

        suspend fun emit(event: TerminalCommandEvent) {
            eventFlow.emit(event)
        }
    }
}
