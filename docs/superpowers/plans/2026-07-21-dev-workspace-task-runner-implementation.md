# Dev Workspace 0.1 Task Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a personal IntelliJ IDEA plugin that loads layered TOML task definitions and runs scripts or inline commands in named, reusable IDEA Terminal tabs with safe auto-start, status tracking, and keyboard actions.

**Architecture:** Keep one Gradle plugin module, with pure Kotlin domain/configuration/runtime-preparation code separated from IntelliJ-specific adapters. Use three TOML repositories, a deterministic resolver, an experimental Build 262 Terminal adapter behind an interface, and project/application services for lifecycle, trust, secrets, and UI state.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 Build 262, Java 25, IntelliJ Platform Gradle Plugin 2.x, Reworked Terminal API, Swing/Kotlin UI DSL, TOMLJ 1.1.1, Kotlin test, IntelliJ Platform test framework.

## Global Constraints

- Repository root: `D:\idea-workspace\java\idea-dev-workspace\Dev Workspace`
- Canonical plugin ID and root package: `com.anmi.devworkspace`
- Target IntelliJ Platform: IntelliJ IDEA 2026.2, build branch `262`
- Minimum supported build: `262`
- JVM toolchain and bytecode target: Java 25
- Terminal dependency ID: `org.jetbrains.plugins.terminal`
- The Terminal API is experimental; all direct references must remain inside `terminal/idea262`.
- Do not support Classic Terminal or IntelliJ versions below 2025.3.
- Keep one Gradle module; organize by packages, not Gradle subprojects.
- Do not block the EDT with disk IO, Password Safe, process waiting, or Terminal initialization.
- Do not log, persist, preview, or include secret values in exceptions.
- Shared auto-start commands execute only after project trust verification.
- Failed tasks never auto-retry or auto-restart.
- Run `gradlew.bat test` and `gradlew.bat buildPlugin` before every implementation commit.
- Do not publish to JetBrains Marketplace.

---

## Planned File Map

```text
build.gradle.kts
gradle.properties
src/main/resources/META-INF/plugin.xml
src/main/resources/messages/DevWorkspaceBundle.properties

src/main/kotlin/com/anmi/devworkspace/
├─ DevWorkspaceBundle.kt
├─ domain/
│  ├─ DevTask.kt
│  ├─ TaskEnums.kt
│  ├─ TaskSource.kt
│  ├─ EnvironmentValue.kt
│  ├─ TaskExecution.kt
│  └─ TaskErrors.kt
├─ config/
│  ├─ TaskTomlParser.kt
│  ├─ TaskTomlWriter.kt
│  ├─ TaskConfigValidator.kt
│  ├─ TaskConfigSnapshot.kt
│  ├─ TaskConfigResolver.kt
│  ├─ TaskRepository.kt
│  ├─ FileTaskRepository.kt
│  ├─ TaskRepositoryPaths.kt
│  ├─ AtomicFileWriter.kt
│  ├─ LastValidSnapshotStore.kt
│  └─ TaskConfigurationService.kt
├─ prepare/
│  ├─ VariableResolver.kt
│  ├─ ShellDetector.kt
│  ├─ ShellQuoter.kt
│  ├─ CommandWrapper.kt
│  └─ TaskPreparationService.kt
├─ secrets/
│  └─ PasswordSafeSecretStore.kt
├─ terminal/
│  ├─ TerminalGateway.kt
│  ├─ TerminalSession.kt
│  ├─ TerminalSessionManager.kt
│  ├─ TerminalOutputMarkerParser.kt
│  └─ idea262/
│     └─ Idea262TerminalGateway.kt
├─ runtime/
│  ├─ TaskExecutionRegistry.kt
│  ├─ TaskRunner.kt
│  ├─ TaskStopService.kt
│  └─ TaskRunHistoryService.kt
├─ trust/
│  ├─ TrustDigest.kt
│  ├─ ProjectTrustService.kt
│  └─ ProjectTrustDialog.kt
├─ startup/
│  ├─ IdeSessionTaskState.kt
│  ├─ DevWorkspaceProjectActivity.kt
│  └─ TaskStartupCoordinator.kt
├─ ui/
│  ├─ DevTasksToolWindowFactory.kt
│  ├─ DevTasksPanel.kt
│  ├─ TaskListModel.kt
│  ├─ TaskListCellRenderer.kt
│  ├─ TaskEditorDialog.kt
│  ├─ TaskConfigErrorPanel.kt
│  └─ RunTaskPopup.kt
└─ actions/
   ├─ OpenDevTasksAction.kt
   ├─ RunSelectedTaskAction.kt
   ├─ StopSelectedTaskAction.kt
   ├─ RunTaskPopupAction.kt
   └─ FavoriteTaskActions.kt

src/test/kotlin/com/anmi/devworkspace/
├─ domain/TaskStatusMachineTest.kt
├─ config/TaskTomlParserTest.kt
├─ config/TaskTomlWriterTest.kt
├─ config/TaskConfigResolverTest.kt
├─ config/AtomicFileWriterTest.kt
├─ config/LastValidSnapshotStoreTest.kt
├─ prepare/VariableResolverTest.kt
├─ prepare/ShellDetectorTest.kt
├─ prepare/ShellQuoterTest.kt
├─ prepare/CommandWrapperTest.kt
├─ terminal/TerminalOutputMarkerParserTest.kt
├─ runtime/TaskExecutionRegistryTest.kt
├─ trust/TrustDigestTest.kt
└─ startup/TaskStartupCoordinatorTest.kt
```

---

### Task 1: Baseline the Empty Plugin and Pin the Platform

**Files:**
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Create: `src/main/resources/messages/DevWorkspaceBundle.properties`
- Create: `src/main/kotlin/com/anmi/devworkspace/DevWorkspaceBundle.kt`
- Delete: wizard sample Kotlin classes and their registrations
- Test: existing `src/test/kotlin` sample test, if present

**Interfaces:**
- Consumes: the wizard-generated empty plugin project.
- Produces: plugin ID `com.anmi.devworkspace`, Java 25 build, Terminal dependency, TOMLJ dependency, test framework.

- [ ] **Step 1: Capture the baseline**

Run from PowerShell:

```powershell
Set-Location "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace"
git status --short
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

Expected: the existing empty plugin compiles. If it does not, preserve the complete failure in `build/reports/baseline.txt` before changing code.

- [ ] **Step 2: Update the platform and dependencies**

Merge these settings into `build.gradle.kts`, retaining the existing Kotlin and IntelliJ Platform Gradle plugin versions already generated by the project:

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdea("2026.2")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
}
```

Set these repository properties in `gradle.properties`:

```properties
group=com.anmi.devworkspace
version=0.1.0-SNAPSHOT
kotlin.stdlib.default.dependency=false
org.gradle.configuration-cache=true
org.gradle.caching=true
```

- [ ] **Step 3: Replace plugin metadata**

Write `src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.anmi.devworkspace</id>
    <name>Dev Workspace</name>
    <vendor>Anmi</vendor>

    <description><![CDATA[
        <p>Personal developer workspace tools for IntelliJ IDEA.</p>
        <p>Version 0.1 provides a TOML-backed task runner using IDEA Terminal.</p>
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.plugins.terminal</depends>

    <resource-bundle>messages.DevWorkspaceBundle</resource-bundle>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>

    <actions>
    </actions>
</idea-plugin>
```

Write `src/main/resources/messages/DevWorkspaceBundle.properties`:

```properties
toolwindow.title=Dev Tasks
action.open.text=Open Dev Tasks
action.run.selected.text=Run Selected Dev Task
action.stop.selected.text=Stop Selected Dev Task
action.run.popup.text=Run Dev Task...
```

Write `src/main/kotlin/com/anmi/devworkspace/DevWorkspaceBundle.kt`:

```kotlin
package com.anmi.devworkspace

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.DevWorkspaceBundle"

object DevWorkspaceBundle : DynamicBundle(BUNDLE) {
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
```

- [ ] **Step 4: Remove sample registrations and code**

Delete wizard-generated sample tool window, startup activity, service, and action classes. Remove all references to those classes from `plugin.xml`.

- [ ] **Step 5: Verify**

Run:

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

Expected: both tasks finish with `BUILD SUCCESSFUL`; the distribution ZIP is under `build/distributions`.

- [ ] **Step 6: Commit**

```powershell
git add build.gradle.kts gradle.properties src
git commit -m "build: target IntelliJ 2026.2 and add terminal dependency"
```

---

### Task 2: Add the Domain Model and Legal State Transitions

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/TaskEnums.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/TaskSource.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/EnvironmentValue.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/DevTask.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/TaskExecution.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/domain/TaskErrors.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/domain/TaskStatusMachineTest.kt`

**Interfaces:**
- Consumes: no project-specific interface.
- Produces: `DevTask`, `ResolvedTask`, `TaskStatus`, `TaskExecution`, and all configuration enums used by every later task.

- [ ] **Step 1: Write the failing transition tests**

```kotlin
package com.anmi.devworkspace.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStatusMachineTest {
    @Test
    fun `running can complete or begin stopping`() {
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED))
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED))
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.STOPPING))
    }

    @Test
    fun `terminal states cannot transition directly`() {
        assertFalse(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.RUNNING))
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.STOPPING))
        assertFalse(TaskStatus.STOPPED.canTransitionTo(TaskStatus.RUNNING))
    }
}
```

- [ ] **Step 2: Run the test and confirm failure**

```powershell
.\gradlew.bat test --tests "*TaskStatusMachineTest"
```

Expected: compilation fails because `TaskStatus` does not exist.

- [ ] **Step 3: Implement enums and sources**

Write `TaskEnums.kt`:

```kotlin
package com.anmi.devworkspace.domain

enum class TaskScope { GLOBAL, PROJECT_SHARED, PROJECT_PRIVATE }
enum class TerminalPolicy { REUSE_TASK_TERMINAL, ALWAYS_NEW, REUSE_SHARED }
enum class AutoStartTrigger { IDE_START, PROJECT_OPEN }
enum class GlobalAutoStartMode { ONCE_PER_IDE_SESSION, ONCE_PER_PROJECT }
enum class RunTrigger { MANUAL, AUTO_IDE_START, AUTO_PROJECT_OPEN, FAVORITE_SLOT }

enum class TaskStatus {
    IDLE,
    PREPARING,
    WAITING_FOR_TERMINAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPING,
    STOPPED,
    UNKNOWN,
    INTERRUPTED;

    fun canTransitionTo(next: TaskStatus): Boolean = next in when (this) {
        IDLE -> setOf(PREPARING)
        PREPARING -> setOf(WAITING_FOR_TERMINAL, FAILED, STOPPED)
        WAITING_FOR_TERMINAL -> setOf(RUNNING, FAILED, STOPPING, STOPPED)
        RUNNING -> setOf(SUCCEEDED, FAILED, STOPPING, UNKNOWN)
        STOPPING -> setOf(STOPPED, FAILED, UNKNOWN)
        SUCCEEDED, FAILED, STOPPED, UNKNOWN, INTERRUPTED -> emptySet()
    }
}
```

Write `TaskSource.kt`:

```kotlin
package com.anmi.devworkspace.domain

sealed interface TaskSource {
    data class ScriptFile(
        val path: String,
        val interpreter: String? = null,
        val arguments: List<String> = emptyList(),
    ) : TaskSource

    data class InlineCommand(
        val command: String,
        val shell: String? = null,
    ) : TaskSource
}
```

Write `EnvironmentValue.kt`:

```kotlin
package com.anmi.devworkspace.domain

sealed interface EnvironmentValue {
    data class Plain(val value: String) : EnvironmentValue
    data class SystemReference(val name: String) : EnvironmentValue
    data class SecretReference(val key: String) : EnvironmentValue
}
```

- [ ] **Step 4: Implement task and execution models**

Write `DevTask.kt`:

```kotlin
package com.anmi.devworkspace.domain

data class AutoStartConfig(
    val trigger: AutoStartTrigger,
    val order: Int = 100,
    val delaySeconds: Int = 0,
    val globalMode: GlobalAutoStartMode = GlobalAutoStartMode.ONCE_PER_IDE_SESSION,
)

data class DevTask(
    val id: String,
    val name: String? = null,
    val terminalAlias: String? = null,
    val description: String? = null,
    val scope: TaskScope,
    val enabled: Boolean = true,
    val source: TaskSource,
    val workingDirectory: String? = null,
    val environment: Map<String, EnvironmentValue> = emptyMap(),
    val terminalPolicy: TerminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
    val exitDetection: Boolean = true,
    val autoStart: AutoStartConfig? = null,
    val favoriteSlot: Int? = null,
)

data class ResolvedTask(
    val effective: DevTask,
    val shadowed: List<DevTask> = emptyList(),
)
```

Write `TaskExecution.kt`:

```kotlin
package com.anmi.devworkspace.domain

import java.time.Instant

data class TaskExecution(
    val taskId: String,
    val executionId: String,
    val trigger: RunTrigger,
    val status: TaskStatus,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val exitCode: Int? = null,
    val failure: TaskFailure? = null,
    val sourceScope: TaskScope,
)

data class TaskRunRecord(
    val taskId: String,
    val executionId: String,
    val startTime: Instant,
    val endTime: Instant?,
    val status: TaskStatus,
    val exitCode: Int?,
    val failureCategory: FailureCategory?,
)
```

Write `TaskErrors.kt`:

```kotlin
package com.anmi.devworkspace.domain

enum class FailureCategory {
    CONFIGURATION,
    PREPARATION,
    SECURITY,
    TERMINAL,
    EXECUTION,
    STATUS_LOST,
}

data class TaskFailure(
    val category: FailureCategory,
    val userMessage: String,
    val technicalMessage: String? = null,
)
```

- [ ] **Step 5: Run all tests**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

Expected: success.

- [ ] **Step 6: Commit**

```powershell
git add src/main/kotlin/com/anmi/devworkspace/domain src/test/kotlin/com/anmi/devworkspace/domain
git commit -m "feat: add task domain model and status transitions"
```

---

### Task 3: Parse and Validate TOML Task Files

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskConfigSnapshot.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskTomlParser.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskConfigValidator.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/TaskTomlParserTest.kt`

**Interfaces:**
- Consumes: domain types from Task 2.
- Produces:
  - `TaskTomlParser.parse(content: String, scope: TaskScope, source: Path): TaskConfigLoadResult`
  - syntax and structural error models with line and column.

- [ ] **Step 1: Write parser tests**

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskTomlParserTest {
    private val parser = TaskTomlParser()

    @Test
    fun `parses script and environment references`() {
        val result = parser.parse(
            content = """
                version = 1

                [[tasks]]
                id = "backend"
                name = "Backend"
                enabled = true
                sourceType = "script"
                script = "${'$'}{PROJECT_DIR}/scripts/start.ps1"
                arguments = ["--profile", "dev"]
                terminalPolicy = "reuse-task"
                exitDetection = true

                [tasks.environment]
                MODE = "dev"
                JAVA_HOME = "${'$'}{ENV:JAVA_HOME}"
                PASSWORD = "${'$'}{SECRET:db-password}"
            """.trimIndent(),
            scope = TaskScope.PROJECT_SHARED,
            source = Path.of("tasks.toml"),
        )

        val success = assertIs<TaskConfigLoadResult.Success>(result)
        val task = success.snapshot.tasks.single()
        assertEquals("backend", task.id)
        assertIs<TaskSource.ScriptFile>(task.source)
        assertIs<EnvironmentValue.SystemReference>(task.environment.getValue("JAVA_HOME"))
        assertIs<EnvironmentValue.SecretReference>(task.environment.getValue("PASSWORD"))
    }

    @Test
    fun `returns positioned syntax error`() {
        val result = parser.parse(
            "version = 1\n[[tasks]]\nid = \"broken",
            TaskScope.GLOBAL,
            Path.of("tasks.toml"),
        )
        val failure = assertIs<TaskConfigLoadResult.Failure>(result)
        assertTrue(failure.errors.first().line >= 1)
        assertTrue(failure.errors.first().column >= 1)
    }

    @Test
    fun `rejects duplicate task ids within one file`() {
        val result = parser.parse(
            """
                version = 1
                [[tasks]]
                id = "same"
                sourceType = "inline"
                command = "echo one"
                [[tasks]]
                id = "same"
                sourceType = "inline"
                command = "echo two"
            """.trimIndent(),
            TaskScope.GLOBAL,
            Path.of("tasks.toml"),
        )
        assertIs<TaskConfigLoadResult.Failure>(result)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

```powershell
.\gradlew.bat test --tests "*TaskTomlParserTest"
```

Expected: compilation failure for missing parser types.

- [ ] **Step 3: Add snapshot and result models**

Write `TaskConfigSnapshot.kt`:

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import java.nio.file.Path
import java.time.Instant

data class TaskConfigSnapshot(
    val scope: TaskScope,
    val tasks: List<DevTask>,
    val sourceFile: Path,
    val contentHash: String,
    val loadedAt: Instant,
)

data class TaskConfigError(
    val sourceFile: Path,
    val line: Int,
    val column: Int,
    val message: String,
)

sealed interface TaskConfigLoadResult {
    data class Success(val snapshot: TaskConfigSnapshot) : TaskConfigLoadResult
    data class Failure(val errors: List<TaskConfigError>) : TaskConfigLoadResult
}
```

- [ ] **Step 4: Implement structural validation**

Write `TaskConfigValidator.kt`:

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask

object TaskConfigValidator {
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun validate(tasks: List<DevTask>): List<String> {
        val errors = mutableListOf<String>()
        val duplicates = tasks.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys

        tasks.forEach { task ->
            if (!idPattern.matches(task.id)) {
                errors += "Task id '${task.id}' must match ${idPattern.pattern}"
            }
            task.favoriteSlot?.let {
                if (it !in 1..9) errors += "Task '${task.id}' favoriteSlot must be between 1 and 9"
            }
            task.autoStart?.let {
                if (it.order < 0) errors += "Task '${task.id}' autoStart.order must be non-negative"
                if (it.delaySeconds < 0) errors += "Task '${task.id}' autoStart.delaySeconds must be non-negative"
            }
        }

        duplicates.forEach { errors += "Duplicate task id '$it' in the same configuration file" }
        return errors
    }
}
```

- [ ] **Step 5: Implement TOML parsing**

Use `org.tomlj.Toml.parse(content)` and map:
- `sourceType = "script"` to `TaskSource.ScriptFile`
- `sourceType = "inline"` to `TaskSource.InlineCommand`
- `${ENV:name}` and `${SECRET:key}` environment values to typed references
- enum values from kebab-case strings
- absent optional fields to model defaults

The public method must be:

```kotlin
class TaskTomlParser {
    fun parse(
        content: String,
        scope: TaskScope,
        source: Path,
    ): TaskConfigLoadResult
}
```

Use SHA-256 for `contentHash`:

```kotlin
private fun sha256(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
```

When TOMLJ reports syntax errors, use each error position for `line` and `column`. For structural errors without a TOML position, use line `1`, column `1`, and include the task ID in the message.

- [ ] **Step 6: Verify**

```powershell
.\gradlew.bat test --tests "*TaskTomlParserTest"
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

Expected: success.

- [ ] **Step 7: Commit**

```powershell
git add src/main/kotlin/com/anmi/devworkspace/config src/test/kotlin/com/anmi/devworkspace/config/TaskTomlParserTest.kt
git commit -m "feat: parse and validate task TOML"
```

---

### Task 4: Write Deterministic TOML and Save Atomically

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskTomlWriter.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/AtomicFileWriter.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/TaskTomlWriterTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/AtomicFileWriterTest.kt`

**Interfaces:**
- Consumes: `DevTask` and `TaskTomlParser`.
- Produces:
  - `TaskTomlWriter.write(tasks: List<DevTask>): String`
  - `AtomicFileWriter.write(path: Path, content: String, validate: (String) -> Unit)`

- [ ] **Step 1: Write round-trip tests**

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskTomlWriterTest {
    @Test
    fun `writer output round trips through parser`() {
        val task = DevTask(
            id = "front-end",
            name = "Frontend \"Dev\"",
            scope = TaskScope.PROJECT_SHARED,
            source = TaskSource.InlineCommand("npm run dev"),
            workingDirectory = "${'$'}{PROJECT_DIR}/frontend",
        )

        val content = TaskTomlWriter().write(listOf(task))
        val parsed = TaskTomlParser().parse(
            content,
            TaskScope.PROJECT_SHARED,
            Path.of("tasks.toml"),
        )

        val success = assertIs<TaskConfigLoadResult.Success>(parsed)
        assertEquals(task, success.snapshot.tasks.single())
    }
}
```

Write `AtomicFileWriterTest.kt` with a temporary directory and assert:
- valid content replaces the file;
- validator failure leaves the original file untouched.

- [ ] **Step 2: Run and confirm failure**

```powershell
.\gradlew.bat test --tests "*TaskTomlWriterTest" --tests "*AtomicFileWriterTest"
```

Expected: missing classes.

- [ ] **Step 3: Implement deterministic writer**

`TaskTomlWriter` must:
- write `version = 1`;
- sort tasks by ID;
- write stable field order;
- use TOML quoted strings with backslash, quote, newline, carriage return, and tab escaping;
- omit default optional values when omission round-trips to the same model;
- write environment keys in lexical order;
- never accept secret values, only `SecretReference.key`.

Core escaping:

```kotlin
private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        append(
            when (ch) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> ch
            }
        )
    }
    append('"')
}
```

- [ ] **Step 4: Implement atomic writer**

```kotlin
package com.anmi.devworkspace.config

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class AtomicFileWriter {
    fun write(path: Path, content: String, validate: (String) -> Unit) {
        validate(content)
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
```

- [ ] **Step 5: Verify**

```powershell
.\gradlew.bat test --tests "*TaskTomlWriterTest" --tests "*AtomicFileWriterTest"
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

Expected: success.

- [ ] **Step 6: Commit**

```powershell
git add src/main/kotlin/com/anmi/devworkspace/config src/test/kotlin/com/anmi/devworkspace/config
git commit -m "feat: serialize task TOML with atomic saves"
```

---

### Task 5: Resolve Three Configuration Layers and Preserve Last Valid Snapshots

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskRepository.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/FileTaskRepository.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskRepositoryPaths.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/LastValidSnapshotStore.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskConfigResolver.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/TaskConfigResolverTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/LastValidSnapshotStoreTest.kt`

**Interfaces:**
- Consumes: parser, writer, snapshot and atomic writer.
- Produces:
  - `TaskRepository.load/save`
  - `TaskConfigResolver.resolve(global, shared, private)`
  - disk-backed last-valid snapshot cache.

- [ ] **Step 1: Write layer resolution tests**

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskConfigResolverTest {
    private fun task(id: String, scope: TaskScope, command: String) = DevTask(
        id = id,
        scope = scope,
        source = TaskSource.InlineCommand(command),
    )

    @Test
    fun `private fully replaces shared and global`() {
        val resolved = TaskConfigResolver.resolve(
            global = listOf(task("server", TaskScope.GLOBAL, "global")),
            shared = listOf(task("server", TaskScope.PROJECT_SHARED, "shared")),
            privateTasks = listOf(task("server", TaskScope.PROJECT_PRIVATE, "private")),
        ).single()

        assertEquals("private", (resolved.effective.source as TaskSource.InlineCommand).command)
        assertEquals(
            listOf(TaskScope.PROJECT_SHARED, TaskScope.GLOBAL),
            resolved.shadowed.map { it.scope },
        )
    }

    @Test
    fun `removing private reveals shared`() {
        val resolved = TaskConfigResolver.resolve(
            global = listOf(task("server", TaskScope.GLOBAL, "global")),
            shared = listOf(task("server", TaskScope.PROJECT_SHARED, "shared")),
            privateTasks = emptyList(),
        ).single()

        assertEquals(TaskScope.PROJECT_SHARED, resolved.effective.scope)
    }
}
```

- [ ] **Step 2: Run and confirm failure**

```powershell
.\gradlew.bat test --tests "*TaskConfigResolverTest"
```

- [ ] **Step 3: Implement resolver**

```kotlin
package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.ResolvedTask

object TaskConfigResolver {
    fun resolve(
        global: List<DevTask>,
        shared: List<DevTask>,
        privateTasks: List<DevTask>,
    ): List<ResolvedTask> {
        val byId = linkedMapOf<String, MutableList<DevTask>>()
        global.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }
        shared.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }
        privateTasks.forEach { byId.getOrPut(it.id) { mutableListOf() } += it }

        return byId.values.map { chain ->
            val ordered = chain.sortedByDescending { it.scope.priority }
            ResolvedTask(ordered.first(), ordered.drop(1))
        }.sortedBy { it.effective.id }
    }

    private val com.anmi.devworkspace.domain.TaskScope.priority: Int
        get() = when (this) {
            com.anmi.devworkspace.domain.TaskScope.GLOBAL -> 0
            com.anmi.devworkspace.domain.TaskScope.PROJECT_SHARED -> 1
            com.anmi.devworkspace.domain.TaskScope.PROJECT_PRIVATE -> 2
        }
}
```

- [ ] **Step 4: Implement repository paths**

Use:
- shared: `<project base>/.dev-workspace/tasks.toml`;
- private: `<IDE system>/dev-workspace/projects/<sha256-normalized-project-path>/tasks.toml`;
- global: `<IDE config>/dev-workspace/tasks.toml`;
- last-valid cache: `<IDE system>/dev-workspace/last-valid/<sha256-source-path>.toml`.

`TaskRepositoryPaths` accepts `projectBase: Path`, `configDir: Path`, and `systemDir: Path` in tests; the IntelliJ service supplies `PathManager.getConfigDir()` and `PathManager.getSystemDir()`.

- [ ] **Step 5: Implement repository and cache**

```kotlin
interface TaskRepository {
    val scope: TaskScope
    val path: Path
    suspend fun load(): TaskConfigLoadResult
    suspend fun save(tasks: List<DevTask>)
}
```

`FileTaskRepository` reads UTF-8, treats a missing file as an empty successful snapshot, and uses `AtomicFileWriter`.

`LastValidSnapshotStore` stores validated TOML content in the system cache. Its API:

```kotlin
class LastValidSnapshotStore(
    private val parser: TaskTomlParser,
    private val writer: AtomicFileWriter,
    private val cacheRoot: Path,
) {
    fun save(snapshot: TaskConfigSnapshot, content: String)
    fun load(source: Path, scope: TaskScope): TaskConfigSnapshot?
}
```

The cache key is SHA-256 of the normalized absolute source path. Validate cached content before returning it.

- [ ] **Step 6: Test disk cache behavior**

Test that:
- a valid snapshot survives a new `LastValidSnapshotStore` instance;
- corrupt cache content returns `null`;
- source paths with different normalized values do not collide.

- [ ] **Step 7: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src/main/kotlin/com/anmi/devworkspace/config src/test/kotlin/com/anmi/devworkspace/config
git commit -m "feat: add layered task repositories and valid snapshot cache"
```

---

### Task 6: Resolve Variables, Detect Shells, Quote Arguments, and Wrap Exit Markers

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/prepare/VariableResolver.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/prepare/ShellDetector.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/prepare/ShellQuoter.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/prepare/CommandWrapper.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/prepare/VariableResolverTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/prepare/ShellDetectorTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/prepare/ShellQuoterTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/prepare/CommandWrapperTest.kt`

**Interfaces:**
- Consumes: domain task types.
- Produces:
  - safe variable resolution without secrets in errors;
  - `ShellType`;
  - shell-specific quoting and marker wrappers.

- [ ] **Step 1: Write variable tests**

Test exact behavior:

```kotlin
val context = VariableContext(
    projectDir = Path.of("C:/work/app"),
    userHome = Path.of("C:/Users/me"),
    moduleDir = null,
    environment = mapOf("JAVA_HOME" to "C:/Java"),
)

assertEquals(
    "C:/work/app/scripts",
    VariableResolver.resolveText("${'$'}{PROJECT_DIR}/scripts", context).getOrThrow()
)
assertTrue(
    VariableResolver.resolveText("${'$'}{MODULE_DIR}", context).isFailure
)
```

Also verify unknown `${WHATEVER}` fails instead of being silently retained.

- [ ] **Step 2: Implement variable resolver**

```kotlin
data class VariableContext(
    val projectDir: Path,
    val userHome: Path,
    val moduleDir: Path?,
    val environment: Map<String, String>,
)

object VariableResolver {
    private val token = Regex("""\$\{([^{}]+)}""")

    fun resolveText(value: String, context: VariableContext): Result<String> = runCatching {
        token.replace(value) { match ->
            when (val key = match.groupValues[1]) {
                "PROJECT_DIR" -> context.projectDir.toString()
                "USER_HOME" -> context.userHome.toString()
                "MODULE_DIR" -> context.moduleDir?.toString()
                    ?: error("MODULE_DIR is unavailable for this task")
                else -> when {
                    key.startsWith("ENV:") -> {
                        val name = key.removePrefix("ENV:")
                        context.environment[name]
                            ?: error("Environment variable '$name' is not defined")
                    }
                    key.startsWith("SECRET:") ->
                        error("Secret references must be resolved by SecretStore")
                    else -> error("Unknown variable '${match.value}'")
                }
            }
        }
    }
}
```

- [ ] **Step 3: Write and implement shell detection**

Define:

```kotlin
enum class ShellType { POWERSHELL, CMD, BASH, ZSH, POSIX_SH, DIRECT }

data class ShellDetectionContext(
    val operatingSystem: OperatingSystem,
    val explicitInterpreter: String?,
    val fileName: String?,
    val firstLine: String?,
    val shellEnvironment: String?,
)

enum class OperatingSystem { WINDOWS, MAC, LINUX }
```

Rules:
- explicit interpreter first;
- shebang second;
- extension third;
- default shell last;
- `.sh` on Windows without explicit interpreter is a failure;
- `.ps1` maps to PowerShell;
- `.bat/.cmd` maps to CMD;
- no-extension executable with no shebang maps to DIRECT;
- Unix default uses `$SHELL`, falling back to POSIX_SH.

- [ ] **Step 4: Implement quoting**

Provide:

```kotlin
interface ShellQuoter {
    fun quote(argument: String): String
}

object PowerShellQuoter : ShellQuoter
object CmdQuoter : ShellQuoter
object PosixShellQuoter : ShellQuoter
```

Required tests:
- spaces;
- quotes;
- ampersands;
- dollar signs;
- empty argument;
- paths ending in backslash.

Do not use one quoting algorithm for all shells.

- [ ] **Step 5: Implement command wrappers**

```kotlin
data class WrappedCommand(
    val text: String,
    val beginMarker: String,
    val exitMarkerPrefix: String,
)

object CommandWrapper {
    fun wrap(
        shellType: ShellType,
        command: String,
        executionId: String,
    ): WrappedCommand
}
```

Markers:

```text
__DEV_TASK_BEGIN__:<executionId>
__DEV_TASK_EXIT__:<executionId>:<exitCode>
```

Use:
- PowerShell: capture `$LASTEXITCODE`, treating `$null` as `0`;
- CMD: capture `%ERRORLEVEL%` immediately after command;
- POSIX: capture `$?` immediately after command.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src/main/kotlin/com/anmi/devworkspace/prepare src/test/kotlin/com/anmi/devworkspace/prepare
git commit -m "feat: prepare portable shell commands safely"
```

---

### Task 7: Add Secret Storage and the Task Preparation Service

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/secrets/PasswordSafeSecretStore.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/prepare/TaskPreparationService.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/prepare/TaskPreparationServiceTest.kt`

**Interfaces:**
- Consumes: variable resolver, shell detector, quoters, command wrappers.
- Produces:
  - `SecretStore`;
  - `TaskPreparationService.prepare(...)`;
  - immutable `PreparedTask`.

- [ ] **Step 1: Define a testable secret interface**

```kotlin
interface SecretStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String?)
}
```

- [ ] **Step 2: Implement Password Safe adapter**

Use service names generated from subsystem `Dev Workspace Tasks` and the supplied key:

```kotlin
private fun attributes(key: String) =
    CredentialAttributes(generateServiceName("Dev Workspace Tasks", key))
```

Perform blocking `PasswordSafe.instance.getPassword()` and `.set()` only inside `withContext(Dispatchers.IO)`. Never log the returned value.

- [ ] **Step 3: Write preparation tests**

Cover:
- existing script and working directory produce `PreparedTask`;
- missing script is `FailureCategory.PREPARATION`;
- missing secret is `FailureCategory.SECURITY`;
- technical messages contain secret key but never any secret value;
- display name priority is terminal alias, script filename, task name, task ID.

- [ ] **Step 4: Implement preparation**

```kotlin
data class PreparedTask(
    val taskId: String,
    val executionId: String,
    val displayName: String,
    val command: String,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val shellType: ShellType,
    val terminalPolicy: TerminalPolicy,
    val exitDetection: Boolean,
    val sourceScope: TaskScope,
)

sealed interface PreparationResult {
    data class Success(val task: PreparedTask) : PreparationResult
    data class Failure(val failure: TaskFailure) : PreparationResult
}
```

`TaskPreparationService.prepare()` must:
1. reject disabled tasks;
2. resolve regular variables;
3. resolve secrets through `SecretStore`;
4. validate directories and scripts;
5. read at most the first script line for shebang;
6. detect shell;
7. quote script path and each argument;
8. build raw or wrapped command;
9. generate a UUID execution ID;
10. return only sanitized failures.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src/main/kotlin/com/anmi/devworkspace/secrets src/main/kotlin/com/anmi/devworkspace/prepare src/test/kotlin/com/anmi/devworkspace/prepare
git commit -m "feat: resolve secrets and prepare task executions"
```

---

### Task 8: Perform a Build 262 Terminal API Spike and Freeze an Adapter Boundary

**Files:**
- Create: `docs/terminal-api-262.md`
- Create: `src/main/kotlin/com/anmi/devworkspace/terminal/TerminalGateway.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/terminal/TerminalSession.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/terminal/TerminalGatewayContractTest.kt`

**Interfaces:**
- Consumes: `PreparedTask`.
- Produces: a stable plugin-owned interface that contains all experimental Terminal API usage behind one adapter.

- [ ] **Step 1: Resolve and inspect the target IDE**

```powershell
.\gradlew.bat printBundledPlugins
.\gradlew.bat dependencies --configuration intellijPlatformDependency
```

Confirm output includes:
- IntelliJ IDEA 2026.2;
- bundled plugin `org.jetbrains.plugins.terminal`.

- [ ] **Step 2: Inspect attached Build 262 source or bytecode**

Using IDE navigation or `javap`, record exact signatures for:

```text
com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
com.intellij.terminal.frontend.view.TerminalView
org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
```

Record in `docs/terminal-api-262.md`:
- how manager instances are obtained;
- builder methods needed for title and working directory;
- return type for building a tab;
- how a tab exposes `TerminalView`;
- `sendText` and builder signatures;
- `shellIntegrationDeferred` type;
- command listener methods;
- how to close a tab;
- how to send Ctrl+C or raw control input.

Do not use APIs annotated `@ApiStatus.Internal` when a documented experimental API exists.

- [ ] **Step 3: Define the plugin-owned terminal boundary**

```kotlin
package com.anmi.devworkspace.terminal

import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask
import kotlinx.coroutines.flow.Flow

data class TerminalSessionKey(
    val projectKey: String,
    val taskId: String,
)

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

interface TerminalGateway {
    suspend fun acquire(
        key: TerminalSessionKey,
        preparedTask: PreparedTask,
        policy: TerminalPolicy,
    ): TerminalSession
}
```

- [ ] **Step 4: Add a fake contract test**

Create a fake `TerminalSession` and test that:
- `execute` records exact text;
- `sendInterrupt` is distinguishable from normal text;
- event flow can report finished exit code.

This protects runtime code from direct Build 262 types.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add docs/terminal-api-262.md src/main/kotlin/com/anmi/devworkspace/terminal src/test/kotlin/com/anmi/devworkspace/terminal
git commit -m "docs: freeze IntelliJ 262 terminal adapter boundary"
```

---

### Task 9: Implement Named and Reusable IDEA Terminal Sessions

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/terminal/TerminalSessionManager.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/terminal/idea262/Idea262TerminalGateway.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/anmi/devworkspace/terminal/TerminalSessionManagerTest.kt`

**Interfaces:**
- Consumes: Task 8 terminal boundary and exact signatures documented in `docs/terminal-api-262.md`.
- Produces: Build 262 Terminal creation, reuse, activation, send, interrupt, close.

- [ ] **Step 1: Write session policy tests against a fake gateway**

Test:
- `REUSE_TASK_TERMINAL` returns the same session for project/task;
- `ALWAYS_NEW` returns different sessions and numbered titles;
- `REUSE_SHARED` returns the same project-level session;
- a closed session is removed and recreated.

- [ ] **Step 2: Implement session manager**

```kotlin
class TerminalSessionManager(
    private val gateway: TerminalGateway,
) {
    private val taskSessions = ConcurrentHashMap<TerminalSessionKey, TerminalSession>()
    private val sharedSessions = ConcurrentHashMap<String, TerminalSession>()

    suspend fun acquire(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession
}
```

Keep policy maps in this class, not in the IDEA adapter.

- [ ] **Step 3: Implement the Build 262 adapter**

In `Idea262TerminalGateway.kt` only:
- obtain `TerminalToolWindowTabsManager`;
- call `createTabBuilder()`;
- set working directory and display title using the exact Build 262 methods recorded in Task 8;
- build/open the tab;
- extract `TerminalView`;
- use `createSendTextBuilder(text).shouldExecute().send()` or the exact recorded equivalent;
- wait for `shellIntegrationDeferred.await()` with `withTimeoutOrNull`;
- subscribe to command execution;
- expose session closure;
- activate the Terminal tool window and tab;
- send Ctrl+C using the documented Build 262 input API;
- close using the manager/tab API.

No other package may import `com.intellij.terminal.frontend.*` or `org.jetbrains.plugins.terminal.view.*`.

- [ ] **Step 4: Add architecture guard**

Add a PowerShell verification script or Gradle check that fails if experimental terminal imports appear outside `terminal/idea262`:

```powershell
$violations = Get-ChildItem src/main/kotlin -Recurse -Filter *.kt |
    Where-Object { $_.FullName -notmatch 'terminal\\idea262' } |
    Select-String 'com\.intellij\.terminal\.frontend|org\.jetbrains\.plugins\.terminal\.view'

if ($violations) {
    $violations | ForEach-Object { Write-Error $_ }
    exit 1
}
```

Save as `scripts/check-terminal-api-boundary.ps1` and run it in verification steps.

- [ ] **Step 5: Verify manually in sandbox**

Run:

```powershell
.\gradlew.bat runIde
```

In the sandbox, temporarily invoke a development action or test hook to create:
- a terminal named `Terminal API Spike`;
- working directory set to the sandbox project;
- command `Write-Output "terminal-ready"`.

Confirm the tab appears and output is correct. Remove the temporary hook before commit.

- [ ] **Step 6: Verify and commit**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src scripts docs
git commit -m "feat: create and reuse IntelliJ terminal sessions"
```

---

### Task 10: Track Execution State and Parse Fallback Markers

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/terminal/TerminalOutputMarkerParser.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/runtime/TaskExecutionRegistry.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/runtime/TaskRunner.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/terminal/TerminalOutputMarkerParserTest.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/runtime/TaskExecutionRegistryTest.kt`

**Interfaces:**
- Consumes: preparation service and terminal manager.
- Produces: one execution path for manual, startup, popup, and favorite actions.

- [ ] **Step 1: Test marker parsing across chunks**

Required cases:
- marker split across two output chunks;
- old execution ID ignored;
- begin and exit markers parsed;
- exit code negative or malformed is ignored safely;
- ordinary user output is ignored.

- [ ] **Step 2: Implement incremental marker parser**

```kotlin
class TerminalOutputMarkerParser(
    private val executionId: String,
) {
    private var tail: String = ""

    fun accept(chunk: String): List<MarkerEvent>
}

sealed interface MarkerEvent {
    data object Began : MarkerEvent
    data class Exited(val code: Int) : MarkerEvent
}
```

Retain only enough tail text to match the longest marker.

- [ ] **Step 3: Test registry transition enforcement**

Test:
- duplicate active execution is rejected;
- illegal transition throws or returns failure;
- terminal states release the active task slot;
- active execution can be queried by task ID.

- [ ] **Step 4: Implement registry**

Use `MutableStateFlow<Map<String, TaskExecution>>` for UI observation and a `Mutex` for mutations.

Public API:

```kotlin
interface TaskExecutionRegistry {
    val executions: StateFlow<Map<String, TaskExecution>>
    suspend fun begin(execution: TaskExecution): Result<Unit>
    suspend fun transition(
        taskId: String,
        expectedExecutionId: String,
        status: TaskStatus,
        exitCode: Int? = null,
        failure: TaskFailure? = null,
    ): Result<TaskExecution>
    fun active(taskId: String): TaskExecution?
}
```

- [ ] **Step 5: Implement runner**

`TaskRunner.run()`:
1. reserves PREPARING;
2. prepares task;
3. moves to WAITING_FOR_TERMINAL;
4. acquires session;
5. waits up to 10 seconds for shell integration;
6. transitions RUNNING before sending;
7. sends command;
8. listens to native command events first;
9. uses marker parser fallback when native exit information is absent;
10. sets SUCCEEDED for exit code 0;
11. sets FAILED for non-zero;
12. sets UNKNOWN if detection is disabled or lost;
13. sanitizes every failure.

The service must receive a coroutine scope through constructor injection:

```kotlin
@Service(Service.Level.PROJECT)
class ProjectTaskRunner(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable
```

- [ ] **Step 6: Verify and commit**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: run tasks with tracked terminal state"
```

---

### Task 11: Add Graceful Stop and Forced Terminal Close

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/runtime/TaskStopService.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/runtime/TaskStopServiceTest.kt`

**Interfaces:**
- Consumes: execution registry and terminal sessions.
- Produces:
  - Ctrl+C stop;
  - timeout result that UI can turn into a force-close confirmation.

- [ ] **Step 1: Write stop behavior tests**

Use fake sessions:
- active RUNNING transitions to STOPPING and receives one interrupt;
- a FINISHED event transitions to STOPPED;
- timeout returns `ForceCloseRequired`;
- no active execution returns `NotRunning`;
- stop never closes automatically.

- [ ] **Step 2: Implement service**

```kotlin
sealed interface StopResult {
    data object Stopped : StopResult
    data object NotRunning : StopResult
    data class ForceCloseRequired(
        val taskId: String,
        val executionId: String,
        val terminalTitle: String,
    ) : StopResult
}

class TaskStopService(
    private val registry: TaskExecutionRegistry,
    private val sessions: TerminalSessionLookup,
) {
    suspend fun stop(taskId: String, timeoutMillis: Long = 5_000): StopResult
    suspend fun forceClose(taskId: String): Result<Unit>
}
```

`stop()` sends interrupt and waits without blocking EDT. `forceClose()` is only called after UI confirmation.

- [ ] **Step 3: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: stop terminal tasks gracefully"
```

---

### Task 12: Build the Configuration Service with Hot Reload

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskConfigurationService.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/config/TaskConfigFileListener.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/config/TaskConfigurationServiceTest.kt`

**Interfaces:**
- Consumes: repositories, last-valid cache, resolver.
- Produces:
  - `StateFlow<List<ResolvedTask>>`;
  - independent errors per scope;
  - save/reload/open-path operations.

- [ ] **Step 1: Write service tests with fake repositories**

Test:
- all three successful layers resolve correctly;
- one failed layer uses its last valid snapshot;
- one failed layer does not erase other layers;
- repairing the layer replaces the cached snapshot;
- a save with external hash mismatch reports conflict.

- [ ] **Step 2: Implement service state**

```kotlin
data class TaskConfigurationState(
    val tasks: List<ResolvedTask> = emptyList(),
    val errors: Map<TaskScope, List<TaskConfigError>> = emptyMap(),
    val snapshots: Map<TaskScope, TaskConfigSnapshot> = emptyMap(),
)

@Service(Service.Level.PROJECT)
class TaskConfigurationService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    val state: StateFlow<TaskConfigurationState>
    suspend fun reload(scope: TaskScope? = null)
    suspend fun save(scope: TaskScope, tasks: List<DevTask>, expectedHash: String?)
    fun path(scope: TaskScope): Path
}
```

- [ ] **Step 3: Add VFS hot reload**

Subscribe through the project message bus to VFS changes. Filter exact normalized TOML paths, debounce 400ms, and call `reload(changedScope)` in the service scope.

Track an internal expected hash after plugin writes. A matching VFS change refreshes silently; a non-matching hash is treated as external editing.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: hot reload layered task configuration"
```

---

### Task 13: Build the Dev Tasks Tool Window

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/DevTasksToolWindowFactory.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/DevTasksPanel.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskListModel.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskListCellRenderer.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskConfigErrorPanel.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/DevWorkspaceBundle.properties`

**Interfaces:**
- Consumes: configuration state, execution registry, runner, stop service.
- Produces: flat task list and standard task operations.

- [ ] **Step 1: Register the tool window**

Add:

```xml
<toolWindow
    id="Dev Tasks"
    anchor="right"
    factoryClass="com.anmi.devworkspace.ui.DevTasksToolWindowFactory"
    canCloseContents="false"/>
```

- [ ] **Step 2: Implement list presentation model**

```kotlin
data class TaskListItem(
    val task: ResolvedTask,
    val status: TaskStatus,
    val statusText: String,
    val failureText: String?,
)
```

Merge configuration and execution flows so UI receives one list sorted by:
1. `autoStart.order` when present;
2. task ID.

No grouping.

- [ ] **Step 3: Implement panel**

Use:
- `SimpleToolWindowPanel`;
- `SearchTextField`;
- `JBList<TaskListItem>`;
- a toolbar with New, Edit, Copy, Delete, Run, Stop, Open Terminal, Open Config;
- empty-state panel with New Task, Create Example Config, Open Config;
- double-click behavior: activate existing terminal, otherwise run.

Keep all actions disabled/enabled from current task status.

- [ ] **Step 4: Implement renderer**

Each row shows:
- task display name;
- `[自动]` or `[手动]`;
- `[全局]`, `[项目共享]`, or `[项目私有]`;
- `[槽位 N]` when set;
- colored platform icon/status text;
- one-line sanitized failure reason;
- override indicator when `shadowed` is non-empty.

Use platform colors and icons; do not hard-code light-theme-only colors.

- [ ] **Step 5: Implement error banner**

`TaskConfigErrorPanel`:
- remains non-modal;
- displays source file, line, column, message;
- provides Open Error Location and Reload;
- hides after successful reload;
- never replaces the task list.

- [ ] **Step 6: Manual sandbox check**

Run `runIde`, open Dev Tasks, and verify:
- empty state is visible;
- example configuration creates a TOML file;
- list populates;
- search filters without altering config;
- invalid TOML shows banner while prior tasks remain.

- [ ] **Step 7: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: add Dev Tasks tool window"
```

---

### Task 14: Add the Native Task Editor and Configuration Navigation

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskEditorDialog.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskEditorModel.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/TaskConfigNavigation.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/ui/TaskEditorModelTest.kt`

**Interfaces:**
- Consumes: writer, configuration service, preparation preview.
- Produces: create/edit/copy/delete through UI, with conflict detection.

- [ ] **Step 1: Test editor model validation**

Test:
- blank/invalid ID rejected;
- favorite slot outside 1–9 rejected;
- script task requires path;
- inline task requires command;
- negative delay rejected;
- secret fields produce `SecretReference`, not plain values;
- switching scopes updates target repository.

- [ ] **Step 2: Implement UI-independent model**

```kotlin
data class TaskEditorState(
    val id: String = "",
    val name: String = "",
    val terminalAlias: String = "",
    val description: String = "",
    val scope: TaskScope = TaskScope.PROJECT_PRIVATE,
    val enabled: Boolean = true,
    val sourceKind: SourceKind = SourceKind.SCRIPT,
    val scriptPath: String = "",
    val arguments: List<String> = emptyList(),
    val interpreter: String = "",
    val inlineCommand: String = "",
    val inlineShell: String = "",
    val workingDirectory: String = "${'$'}{PROJECT_DIR}",
    val terminalPolicy: TerminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
    val exitDetection: Boolean = true,
    val autoStartTrigger: AutoStartTrigger? = null,
    val startupOrder: Int = 100,
    val startupDelaySeconds: Int = 0,
    val globalMode: GlobalAutoStartMode = GlobalAutoStartMode.ONCE_PER_IDE_SESSION,
    val favoriteSlot: Int? = null,
    val environmentRows: List<EnvironmentRow> = emptyList(),
)
```

- [ ] **Step 3: Implement DialogWrapper**

Use Kotlin UI DSL sections:
- Basic;
- Execution;
- Runtime;
- Auto-start;
- Shortcut;
- Preview.

Preview:
- resolved working directory;
- detected shell;
- sanitized final command;
- validation warnings;
- secret placeholders only.

- [ ] **Step 4: Add save conflict handling**

Capture snapshot hash when opening. On save:
- same hash: save normally;
- changed hash: offer Reload and Edit, View Diff, or Overwrite;
- use IDEA Diff Viewer for View Diff;
- deletion of a private override immediately reveals the shared/global task.

- [ ] **Step 5: Add config navigation**

Open TOML with `FileEditorManager`, move caret to exact error line/column when requested, and provide “Show in Explorer” via platform file manager utility.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: edit task configurations in native dialog"
```

---

### Task 15: Add Trust Digests and Safe Shared Auto-Start

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/trust/TrustDigest.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/trust/ProjectTrustService.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/trust/ProjectTrustDialog.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/trust/TrustDigestTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: resolved tasks.
- Produces:
  - stable execution-only digest;
  - local persisted trust decision.

- [ ] **Step 1: Write digest tests**

Assert:
- changing name, description, favorite slot, or terminal alias does not change digest;
- changing command, script, args, workdir, interpreter, environment references, enabled, or auto-start does change digest;
- secret reference key affects digest, secret value does not enter digest.

- [ ] **Step 2: Implement canonical digest**

Create canonical lines sorted by task ID and environment key. Include only shared, enabled, auto-start tasks and execution-affecting fields. Hash with SHA-256.

- [ ] **Step 3: Implement persistent trust service**

```kotlin
@Service(Service.Level.APP)
@State(
    name = "DevWorkspaceProjectTrust",
    storages = [Storage("dev-workspace-trust.xml")],
)
class ProjectTrustService : PersistentStateComponent<ProjectTrustState>
```

Store:
- normalized project path;
- digest;
- trusted timestamp.

Do not write trust to project files.

- [ ] **Step 4: Implement trust dialog**

Display:
- count and names of shared auto-start tasks;
- warning that local commands will execute;
- View Configuration;
- Trust and Allow;
- Do Not Allow.

Only Trust and Allow persists the exact path/digest pair.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: protect shared auto-start tasks with project trust"
```

---

### Task 16: Coordinate IDEA-Start and Project-Open Tasks

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/startup/IdeSessionTaskState.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/startup/TaskStartupCoordinator.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/startup/DevWorkspaceProjectActivity.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/startup/TaskStartupCoordinatorTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Consumes: configuration, trust service, runner.
- Produces: ordered, delayed submissions without waiting for long-running commands.

- [ ] **Step 1: Write coordinator tests with a virtual/fake scheduler**

Test:
- tasks sorted by order;
- each delay is relative to its turn;
- next task is submitted without waiting for prior completion;
- active task is skipped;
- global once-per-session task runs only in the first opened project;
- global once-per-project task runs in every project;
- untrusted shared tasks never run;
- closing coordinator cancels pending delays.

- [ ] **Step 2: Implement application session state**

```kotlin
@Service(Service.Level.APP)
class IdeSessionTaskState {
    val sessionId: String = UUID.randomUUID().toString()
    val executedGlobalTaskIds = ConcurrentHashMap.newKeySet<String>()
}
```

No application-start activity is needed. IDE-start tasks wait until the first project activity.

- [ ] **Step 3: Implement coordinator**

Use project service coroutine scope. On project ready:
1. await initial configuration load;
2. identify eligible tasks;
3. ask trust once for shared auto-start tasks;
4. sort by order and task ID;
5. delay each task;
6. check active registry immediately before submission;
7. call runner;
8. record once-per-session global tasks.

- [ ] **Step 4: Register ProjectActivity**

```xml
<postStartupActivity
    implementation="com.anmi.devworkspace.startup.DevWorkspaceProjectActivity"/>
```

The Kotlin `ProjectActivity.execute(project)` delegates to coordinator and returns without blocking UI.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: run trusted tasks on project and IDE startup"
```

---

### Task 17: Register Fixed Actions, Search Popup, and Nine Favorite Slots

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/actions/OpenDevTasksAction.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/actions/RunSelectedTaskAction.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/actions/StopSelectedTaskAction.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/actions/RunTaskPopupAction.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/actions/FavoriteTaskActions.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/ui/RunTaskPopup.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/com/anmi/devworkspace/actions/FavoriteSlotResolverTest.kt`

**Interfaces:**
- Consumes: tool window, configuration state, runner, stop service.
- Produces: Keymap-addressable fixed actions.

- [ ] **Step 1: Test favorite resolution**

Test:
- one effective task in slot resolves;
- overridden task gives slot to effective definition;
- duplicate slots among different effective tasks return conflict;
- disabled task does not execute.

- [ ] **Step 2: Implement actions**

Register:
- `DevWorkspace.OpenTasks`;
- `DevWorkspace.RunSelected`;
- `DevWorkspace.StopSelected`;
- `DevWorkspace.RunTaskPopup`;
- `DevWorkspace.RunFavorite1` through `DevWorkspace.RunFavorite9`.

Use nine no-argument subclasses over a base action:

```kotlin
abstract class RunFavoriteTaskAction(
    private val slot: Int,
) : DumbAwareAction() {
    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<FavoriteTaskExecutor>().run(slot)
    }
}

class RunFavoriteTask1Action : RunFavoriteTaskAction(1)
// through RunFavoriteTask9Action
```

- [ ] **Step 3: Implement searchable popup**

Use IntelliJ popup APIs with:
- task name;
- task ID;
- scope tag;
- current status;
- keyboard filtering;
- Enter to run;
- active task selection activates terminal instead of silently duplicating.

- [ ] **Step 4: Register plugin.xml actions**

Place Open and Run Popup in Tools menu and Find Action. Do not assign hard-coded default shortcuts; the user configures Keymap.

- [ ] **Step 5: Verify manually**

In sandbox:
- bind a shortcut to Favorite 1;
- assign a task to slot 1;
- invoke from editor and Terminal focus;
- verify popup filters and runs;
- verify conflicting slots show a visible configuration error.

- [ ] **Step 6: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: add task actions and favorite shortcut slots"
```

---

### Task 18: Persist Lightweight Run History and Clean Up Lifecycles

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/runtime/TaskRunHistoryService.kt`
- Modify: runtime and UI files to publish/display last result
- Test: `src/test/kotlin/com/anmi/devworkspace/runtime/TaskRunHistoryServiceTest.kt`

**Interfaces:**
- Consumes: execution registry transitions.
- Produces: up to 20 sanitized records per task and interrupted-session restoration.

- [ ] **Step 1: Write history tests**

Test:
- retains newest 20 records per task;
- never persists technical messages or environment values;
- RUNNING and STOPPING deserialize as INTERRUPTED after restart;
- terminal output is absent.

- [ ] **Step 2: Implement persistent state**

Use project-level `PersistentStateComponent`. Persist:
- task ID;
- execution ID;
- times;
- status;
- exit code;
- failure category.

Do not persist failure technical details.

- [ ] **Step 3: Connect registry and UI**

On terminal state:
- append/update record;
- list displays most recent terminal result;
- startup maps stale active states to INTERRUPTED.

- [ ] **Step 4: Ensure disposal**

Project services must cancel:
- file watch debounce jobs;
- auto-start delay jobs;
- terminal event collectors;
- pending task preparation.

They must not send interrupts or wait for commands during project disposal.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
git add src
git commit -m "feat: persist sanitized task run history"
```

---

### Task 19: Complete End-to-End Verification and User Documentation

**Files:**
- Create: `docs/manual-test-checklist.md`
- Create: `docs/configuration.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`, if present
- Modify: `src/main/resources/META-INF/plugin.xml` description/change notes if required

**Interfaces:**
- Consumes: completed plugin.
- Produces: a locally installable and documented 0.1 ZIP.

- [ ] **Step 1: Write configuration documentation**

Document:
- all TOML fields;
- three scopes and exact paths;
- override semantics;
- variables;
- secrets;
- shell rules;
- terminal policies;
- auto-start;
- trust;
- favorite slots;
- error recovery.

Include complete PowerShell and inline-command examples.

- [ ] **Step 2: Write the manual test checklist**

Include the 17 scenarios from the design specification, with checkboxes and an Actual Result field.

- [ ] **Step 3: Run automated verification**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
.\gradlew.bat clean test
.\gradlew.bat buildPlugin
```

Expected: all pass.

- [ ] **Step 4: Run sandbox verification**

```powershell
.\gradlew.bat runIde
```

Verify at minimum:
1. shared PowerShell script starts after Conda initialization;
2. same task reuses its terminal;
3. inline command works;
4. Ctrl+C stop works;
5. force-close prompt appears after timeout;
6. invalid TOML preserves the last valid list;
7. TOML repair reloads automatically;
8. private override replaces shared/global;
9. deleting private override reveals shared;
10. trust prompt appears for shared auto-start;
11. command change invalidates trust;
12. name-only change does not invalidate trust;
13. global IDE-start runs once in first project;
14. Favorite Slot invokes task;
15. no secret appears in logs, previews, notifications, or stored XML;
16. project closes without waiting;
17. restart marks stale running state interrupted.

- [ ] **Step 5: Install the built ZIP in the normal IDE**

Use:

```text
Settings → Plugins → Gear → Install Plugin from Disk
```

Select the ZIP from `build/distributions`. Restart and repeat a smoke test outside the sandbox.

- [ ] **Step 6: Inspect logs and plugin contents**

Open IDEA log and search for:
- EDT blocking assertions;
- leaked disposables;
- experimental API exceptions;
- secrets or secret test values.

Inspect ZIP/JAR contents and confirm TOMLJ is packaged as a separate library under the plugin `lib` directory.

- [ ] **Step 7: Final commit**

```powershell
git add README.md CHANGELOG.md docs src build.gradle.kts gradle.properties scripts
git commit -m "docs: complete Dev Workspace task runner 0.1"
git status --short
```

Expected: clean working tree.

---

## Plan Self-Review

### Specification Coverage

- Domain and scopes: Tasks 2 and 5.
- TOML and last valid backup: Tasks 3–5 and 12.
- Variables, shell detection, quoting: Task 6.
- Password Safe and secret hygiene: Task 7.
- Build 262 Terminal API, reuse, wait for Conda: Tasks 8–9.
- Accurate status with marker fallback: Task 10.
- Stop and force close: Task 11.
- Flat Tool Window, errors, editor: Tasks 13–14.
- Trust and automatic startup: Tasks 15–16.
- Fixed actions, popup, nine slots: Task 17.
- Run history and lifecycle: Task 18.
- Full build, install, and manual checks: Task 19.

### Required Review Checkpoints

Pause for human review after:
1. Task 7 — all pure logic and storage foundations pass.
2. Task 9 — Build 262 Terminal creation and command sending work in sandbox.
3. Task 14 — Tool Window and editor can manage tasks.
4. Task 17 — startup, trust, and shortcut flows work.
5. Task 19 — final local installation succeeds.

### Implementation Discipline

- Follow TDD for all pure logic.
- Keep direct experimental Terminal imports inside `terminal/idea262`.
- Commit after every task.
- Do not continue past a failed test or build.
- Do not silently change the specification; record requested deviations in the design document first.
