package com.anmi.devworkspace.terminal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class TerminalCommandEvent(
    val executionId: String?,
    val state: TerminalCommandState,
    val exitCode: Int? = null,
    val outputText: String? = null,
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

internal class TerminalEventStream {
    private val lock = Any()
    private val subscribers = LinkedHashSet<Channel<TerminalCommandEvent>>()
    private var terminalEvent: TerminalCommandEvent? = null

    val events: Flow<TerminalCommandEvent> = flow {
        val channel = Channel<TerminalCommandEvent>(Channel.UNLIMITED)
        synchronized(lock) {
            val closed = terminalEvent
            if (closed == null) {
                subscribers += channel
            } else {
                channel.trySend(closed)
                channel.close()
            }
        }
        try {
            for (event in channel) emit(event)
        } finally {
            synchronized(lock) {
                subscribers.remove(channel)
                channel.cancel()
            }
        }
    }

    fun publish(event: TerminalCommandEvent) {
        synchronized(lock) {
            if (terminalEvent != null) return
            val iterator = subscribers.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().trySend(event).isFailure) iterator.remove()
            }
        }
    }

    fun close(event: TerminalCommandEvent) {
        synchronized(lock) {
            if (terminalEvent != null) return
            terminalEvent = event
            subscribers.forEach { subscriber ->
                subscriber.trySend(event)
                subscriber.close()
            }
            subscribers.clear()
        }
    }
}
