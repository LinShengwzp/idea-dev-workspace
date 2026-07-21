# IntelliJ IDEA 2026.2 Terminal API Findings

## Resolved platform

The Task 8 spike inspected the locally resolved IntelliJ IDEA distribution, not an assumed API surface.

- Gradle platform dependency: `idea:idea:2026.2`
- Resolved product/build: IntelliJ IDEA `2026.2`, build `262.8665.258`
- Bundled dependency: `org.jetbrains.plugins.terminal` (`Terminal`)
- Frontend classes: `plugins/terminal/lib/modules/intellij.terminal.frontend.jar`
- Shared Terminal classes: `plugins/terminal/lib/terminal.jar`

The signatures below were obtained with `javap -p` and annotations were checked with `javap -v` against those JARs.

## Tool-window tabs

`com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager` is `@ApiStatus.Experimental` and `@ApiStatus.NonExtendable`.

```kotlin
interface TerminalToolWindowTabsManager {
    val tabs: List<TerminalToolWindowTab> // @RequiresEdt
    fun createTabBuilder(): TerminalToolWindowTabBuilder
    fun closeTab(tab: TerminalToolWindowTab) // @RequiresEdt
    fun detachTab(tab: TerminalToolWindowTab): TerminalView // @RequiresEdt
    fun attachTab(
        view: TerminalView,
        contentManager: ContentManager,
        closeOnProcessTermination: Boolean,
    ): TerminalToolWindowTab // @RequiresEdt

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TerminalToolWindowTabsManager
    }
}
```

Obtain the manager with `TerminalToolWindowTabsManager.getInstance(project)`.

`TerminalToolWindowTabBuilder` is also experimental and non-extendable. The relevant public methods are:

```kotlin
fun workingDirectory(directory: String): TerminalToolWindowTabBuilder
fun shellCommand(command: List<String>): TerminalToolWindowTabBuilder
fun envVariables(envs: Map<String, String>): TerminalToolWindowTabBuilder
fun processType(processType: TerminalProcessType): TerminalToolWindowTabBuilder
fun tabName(name: String): TerminalToolWindowTabBuilder
fun requestFocus(requestFocus: Boolean): TerminalToolWindowTabBuilder
fun deferSessionStartUntilUiShown(defer: Boolean): TerminalToolWindowTabBuilder
fun contentManager(manager: ContentManager): TerminalToolWindowTabBuilder
fun closeOnProcessTermination(shouldClose: Boolean): TerminalToolWindowTabBuilder
fun createTab(): TerminalToolWindowTab // @RequiresEdt
```

Task creation needs `workingDirectory(...)`, `envVariables(...)`, `tabName(...)`, `requestFocus(...)`, and `createTab()`. `createTab()` returns `TerminalToolWindowTab`, not `TerminalView` directly.

The following builder methods are `@ApiStatus.Internal` and must not be used:

- `shouldAddToToolWindow(Boolean)`
- `sourceNavigationProjectPath(String)`
- `startupFusInfo(TerminalStartupFusInfo)`

`TerminalToolWindowTab` is experimental and non-extendable:

```kotlin
interface TerminalToolWindowTab {
    val view: TerminalView
    val content: Content
    val closeOnProcessTermination: Boolean
}
```

Therefore the view is obtained with `tab.view`. Close the tab with `manager.closeTab(tab)` on the EDT. There is no dedicated experimental `activate()` method on the tab; activation must use the exposed `Content` with the normal tool-window/content-manager API on the EDT.

## Terminal view and command sending

`com.intellij.terminal.frontend.view.TerminalView` is `@ApiStatus.Experimental` and `@ApiStatus.NonExtendable`. Relevant signatures are:

```kotlin
val coroutineScope: CoroutineScope
val component: JComponent
val preferredFocusableComponent: JComponent
val sessionState: StateFlow<TerminalViewSessionState>
val shellIntegrationDeferred: Deferred<TerminalShellIntegration>
val currentDirectory: String?

suspend fun hasChildProcesses(): Boolean
fun sendText(text: String)
fun createSendTextBuilder(): TerminalSendTextBuilder
```

`shellIntegrationDeferred` is exactly `kotlinx.coroutines.Deferred<TerminalShellIntegration>`. It can be awaited with a coroutine timeout; it must never be awaited on the EDT.

For command execution, use:

```kotlin
view.createSendTextBuilder()
    .shouldExecute()
    .send(text)
```

`shouldExecute()` requests execution after inserting the text. Plain `view.sendText(text)` delegates to `createSendTextBuilder().send(text)` and does not opt into `shouldExecute()`.

`org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder` is experimental and non-extendable:

```kotlin
fun shouldExecute(): TerminalSendTextBuilder
fun useBracketedPasteMode(): TerminalSendTextBuilder
fun requireBracketedPasteMode(): TerminalSendTextBuilder
fun sendEndKeyBeforeText(): TerminalSendTextBuilder
fun send(text: String)
fun trySend(text: String): Boolean
```

For Ctrl+C, the available non-internal route is `view.sendText("\u0003")`. The lower-level `TerminalView.sessionDeferred`, `TerminalSession.inputChannel`, and `TerminalWriteBytesEvent` route is internal; `TerminalWriteBytesEvent` is explicitly `@ApiStatus.Internal` and is excluded from the adapter. Other raw control characters, when needed, must likewise use `sendText(String)` rather than the internal byte channel.

The following `TerminalView` members are internal and must not be used by this plugin:

- `sessionDeferred`
- `addInputInterceptor(...)`
- `setTopComponent(...)`

## Shell Integration and command events

`org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration` is experimental and non-extendable:

```kotlin
interface TerminalShellIntegration {
    val blocksModel: TerminalBlocksModel
    val outputStatus: StateFlow<TerminalOutputStatus>
    val commandAliases: Map<String, String>

    fun addCommandExecutionListener(
        parentDisposable: Disposable,
        listener: TerminalCommandExecutionListener,
    )
}
```

`addShellBasedCompletionListener(...)` is internal and is not part of the adapter.

`TerminalCommandExecutionListener` is experimental and provides default callbacks:

```kotlin
interface TerminalCommandExecutionListener {
    fun commandStarted(event: TerminalCommandStartedEvent)
    fun commandFinished(event: TerminalCommandFinishedEvent)
}
```

Both events expose `outputModel` through `TerminalCommandExecutionEvent` and `commandBlock` through their concrete event interface. Exit status is available as the nullable value `event.commandBlock.exitCode: Int?` on `commandFinished`.

## Frozen plugin boundary

All types above are confined to `com.anmi.devworkspace.terminal.idea262`. Code outside that package depends only on the plugin-owned `TerminalGateway`, `TerminalSession`, `TerminalCommandEvent`, and related value types.

The Task 8 boundary intentionally does not create tabs, send commands, or register listeners. Those behaviors belong to the Build 262 adapter implemented and sandbox-verified in Task 9.
