package com.anmi.devworkspace.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `output changed event carries a plugin owned text chunk`() = runBlocking {
        val session = FakeTerminalSession()

        session.emit(
            TerminalCommandEvent(
                executionId = null,
                state = TerminalCommandState.OUTPUT_CHANGED,
                outputText = "__DEV_TASK_EXIT__:execution-1:0",
            ),
        )

        assertEquals(
            "__DEV_TASK_EXIT__:execution-1:0",
            session.events.first().outputText,
        )
    }

    @Test
    fun `event stream is lossless and completes after session close`() = runBlocking {
        val stream = TerminalEventStream()
        val received = mutableListOf<TerminalCommandEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            stream.events.collect { event ->
                delay(2)
                received += event
            }
        }

        repeat(100) { index ->
            stream.publish(
                TerminalCommandEvent(
                    executionId = null,
                    state = TerminalCommandState.OUTPUT_CHANGED,
                    outputText = if (index == 75) "__DEV_TASK_EXIT__:execution-1:0" else "chunk-$index",
                ),
            )
        }
        stream.close(
            TerminalCommandEvent(
                executionId = null,
                state = TerminalCommandState.SESSION_CLOSED,
            ),
        )

        withTimeout(5_000) { collector.join() }
        assertEquals(101, received.size)
        assertEquals("__DEV_TASK_EXIT__:execution-1:0", received[75].outputText)
        assertEquals(TerminalCommandState.SESSION_CLOSED, received.last().state)
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
