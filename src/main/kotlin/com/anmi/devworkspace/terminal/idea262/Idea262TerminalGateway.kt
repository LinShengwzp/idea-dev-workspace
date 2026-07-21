package com.anmi.devworkspace.terminal.idea262

import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask
import com.anmi.devworkspace.terminal.TerminalCommandEvent
import com.anmi.devworkspace.terminal.TerminalCommandState
import com.anmi.devworkspace.terminal.TerminalEventStream
import com.anmi.devworkspace.terminal.TerminalGateway
import com.anmi.devworkspace.terminal.TerminalSession
import com.anmi.devworkspace.terminal.TerminalSessionKey
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandStartedEvent
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class Idea262TerminalGateway(
    private val project: Project,
) : TerminalGateway {
    override suspend fun acquire(
        key: TerminalSessionKey,
        preparedTask: PreparedTask,
        policy: TerminalPolicy,
    ): TerminalSession {
        val tabsManager = TerminalToolWindowTabsManager.getInstance(project)
        val tab = withContext(Dispatchers.EDT) {
            tabsManager.createTabBuilder()
                .workingDirectory(preparedTask.workingDirectory.toString())
                .envVariables(preparedTask.environment)
                .tabName(preparedTask.displayName)
                .requestFocus(false)
                .createTab()
        }
        return Idea262TerminalSession(project, tabsManager, tab, preparedTask.displayName)
    }
}

private class Idea262TerminalSession(
    private val project: Project,
    private val tabsManager: TerminalToolWindowTabsManager,
    private val tab: TerminalToolWindowTab,
    override val title: String,
) : TerminalSession {
    private val view: TerminalView = tab.view
    private val eventStream = TerminalEventStream()
    private val listenerDisposable: Disposable = Disposer.newDisposable("Dev Workspace terminal session")
    private val shellIntegrationMutex = Mutex()
    private val closed = AtomicBoolean()
    private var shellIntegrationInitialized = false

    override val id: String = UUID.randomUUID().toString()
    override val events: Flow<TerminalCommandEvent> = eventStream.events

    init {
        Disposer.register(project, listenerDisposable)
        registerOutputListeners()
        view.coroutineScope.launch {
            view.sessionState.first { it is TerminalViewSessionState.Terminated }
            publishClosed()
        }
    }

    override suspend fun awaitReady(timeoutMillis: Long): Boolean = try {
        withTimeoutOrNull(timeoutMillis) {
            shellIntegrationMutex.withLock {
                if (!shellIntegrationInitialized) {
                    registerCommandListener()
                    shellIntegrationInitialized = true
                }
            }
            true
        } ?: false
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: RuntimeException) {
        false
    }

    override suspend fun execute(text: String) {
        view.createSendTextBuilder()
            .shouldExecute()
            .send(text)
    }

    override suspend fun sendInterrupt() {
        view.sendText(CONTROL_C)
    }

    override suspend fun activate() {
        withContext(Dispatchers.EDT) {
            tab.content.manager?.setSelectedContent(tab.content)
            ToolWindowManager.getInstance(project)
                .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                ?.activate { view.preferredFocusableComponent.requestFocusInWindow() }
        }
    }

    override suspend fun close() {
        try {
            withContext(Dispatchers.EDT) {
                tabsManager.closeTab(tab)
            }
        } finally {
            publishClosed()
        }
    }

    private suspend fun registerCommandListener() {
        val shellIntegration = view.shellIntegrationDeferred.await()
        shellIntegration.addCommandExecutionListener(
            listenerDisposable,
            object : TerminalCommandExecutionListener {
                override fun commandStarted(event: TerminalCommandStartedEvent) {
                    publish(
                        TerminalCommandEvent(
                            executionId = null,
                            state = TerminalCommandState.STARTED,
                        ),
                    )
                }

                override fun commandFinished(event: TerminalCommandFinishedEvent) {
                    publish(
                        TerminalCommandEvent(
                            executionId = null,
                            state = TerminalCommandState.FINISHED,
                            exitCode = event.commandBlock.exitCode,
                        ),
                    )
                }
            },
        )
    }

    private fun registerOutputListeners() {
        val listener = object : TerminalOutputModelListener {
            override fun afterContentChanged(event: TerminalContentChangeEvent) {
                val newText = event.newText.toString()
                if (!event.isTypeAhead && !event.isTrimming && newText.isNotEmpty()) {
                    publish(
                        TerminalCommandEvent(
                            executionId = null,
                            state = TerminalCommandState.OUTPUT_CHANGED,
                            outputText = newText,
                        ),
                    )
                }
            }
        }
        view.outputModels.regular.addListener(listenerDisposable, listener)
        view.outputModels.alternative.addListener(listenerDisposable, listener)
    }

    private fun publish(event: TerminalCommandEvent) {
        if (!closed.get()) eventStream.publish(event)
    }

    private fun publishClosed() {
        if (closed.compareAndSet(false, true)) {
            eventStream.close(
                TerminalCommandEvent(
                    executionId = null,
                    state = TerminalCommandState.SESSION_CLOSED,
                ),
            )
            Disposer.dispose(listenerDisposable)
        }
    }

    private companion object {
        const val CONTROL_C = "\u0003"
    }
}
