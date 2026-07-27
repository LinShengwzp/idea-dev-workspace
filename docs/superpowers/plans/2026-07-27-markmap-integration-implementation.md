# Dev Workspace MarkMap Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the standalone MarkMap Preview plugin into Dev Workspace while preserving its current interaction, adding configurable filename suffixes, and keeping Gradle independent from Node/npm.

**Architecture:** Treat the old project as a read-only source. Migrate only the preview panel, project service, Tool Window, Action, WebView source, and built HTML into the existing Dev Workspace module. Add an application-level suffix setting and a pure matcher used by the Action.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 Build 262, JCEF, IntelliJ Action System, PersistentStateComponent, Swing/Kotlin UI DSL, Vite, TypeScript, Markmap.

## Global Constraints

- Source project: `D:\idea-workspace\java\idea-markmap-preview\idea-markmap-preview`
- Target project: `D:\idea-workspace\java\idea-dev-workspace\Dev Workspace`
- Source project is read-only.
- Target branch is the current Dev Workspace feature branch.
- Destination package is `com.anmi.devworkspace.markmap`.
- Do not migrate old plugin ID, vendor, version, Gradle project, or publishing configuration.
- Do not migrate `MarkmapPreviewHolder.kt`.
- Do not copy the old `plugin.xml` wholesale.
- Keep the existing MarkMap interaction: context Action opens the right-side Markmap Tool Window.
- Only configured full filename suffixes are recognized.
- Default suffixes are `mm.md` and `mm.markdown`.
- Settings live at `Settings → Tools → Dev Workspace → MarkMap`.
- Settings are application-level and require no old-config migration.
- Gradle `test`, `runIde`, and `buildPlugin` must not invoke npm.
- External WebView source is retained under `webview/markmap`.
- Runtime uses the committed generated file `src/main/resources/markmap/index.html`.
- UI strings use `DevWorkspaceBundle.properties`.
- Inspect actual icon filenames under `src/main/resources/icons` before referencing one.
- Do not assign a default shortcut.
- Keep JCEF resources disposable.
- Run focused tests, full `test`, `buildPlugin`, and Terminal API boundary checks before every commit.
- Commit each numbered task separately.
- Pause after Tasks 2, 4, and 6.

---

### Task 1: Import WebView Source and Runtime Resource

**Files:**
- Create directory: `webview/markmap/`
- Copy: source `webview/index.html` → target `webview/markmap/index.html`
- Copy: source `webview/package.json` → target `webview/markmap/package.json`
- Copy: source `webview/src/main.ts` → target `webview/markmap/src/main.ts`
- Create/modify: `webview/markmap/vite.config.ts`
- Copy: source `src/main/resources/markmap/index.html` → target `src/main/resources/markmap/index.html`
- Modify: target `.gitignore` if `node_modules/` is not already ignored.
- Create: `docs/markmap-webview-development.md`

**Interfaces:**
- Produces runtime resource path `/markmap/index.html`.
- Produces manual frontend build command `npm run build`.
- Does not modify Gradle task dependencies.

- [ ] **Step 1: Inventory the source and target**

Verify the source files exist and inspect the target for conflicting resources:

```powershell
Get-ChildItem "D:\idea-workspace\java\idea-markmap-preview\idea-markmap-preview\webview" -Recurse
Get-ChildItem "src\main\resources\markmap" -ErrorAction SilentlyContinue
Get-ChildItem "webview\markmap" -ErrorAction SilentlyContinue
```

Stop if a target MarkMap resource already exists with unrelated content.

- [ ] **Step 2: Copy the WebView source and built resource**

Copy only the listed files. Do not copy `node_modules`, old Gradle files, or old plugin metadata.

- [ ] **Step 3: Change Vite output directory**

Use:

```ts
import { defineConfig } from 'vite'
import { viteSingleFile } from 'vite-plugin-singlefile'

export default defineConfig({
  plugins: [viteSingleFile()],
  build: {
    outDir: '../../src/main/resources/markmap',
    emptyOutDir: true
  }
})
```

- [ ] **Step 4: Ensure Gradle has no npm dependency**

Search the target:

```powershell
Get-ChildItem -Recurse -File | Select-String -Pattern "buildMarkmapWebview|npm install|npm run build"
```

Expected: no Gradle build hook. Documentation occurrences are allowed.

- [ ] **Step 5: Write WebView development documentation**

Document:

```powershell
cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace\webview\markmap"
npm install
npm run build

cd "D:\idea-workspace\java\idea-dev-workspace\Dev Workspace"
.\gradlew.bat test
.\gradlew.bat buildPlugin
```

State explicitly:

- generated HTML is not hand-edited;
- `node_modules/` is not committed;
- generated HTML is committed;
- first `npm install` creates a lock file, which should be committed;
- ordinary Gradle builds do not need Node.

- [ ] **Step 6: Verify resource packaging**

Run:

```powershell
.\gradlew.bat processResources
```

Confirm the processed resources contain `markmap/index.html`.

- [ ] **Step 7: Commit**

```bash
git add webview/markmap src/main/resources/markmap docs/markmap-webview-development.md .gitignore
git commit -m "feat: import MarkMap webview resources"
```

---

### Task 2: Add Application Settings and Suffix Matcher

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/MarkmapSuffixMatcher.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/settings/MarkmapSettingsState.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/settings/MarkmapConfigurable.kt`
- Create or modify the Dev Workspace settings parent at the existing settings package.
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/DevWorkspaceBundle.properties`
- Test: `src/test/kotlin/com/anmi/devworkspace/markmap/MarkmapSuffixMatcherTest.kt`
- Test: settings state/configurable model tests following project conventions.

**Interfaces:**
- Produces `MarkmapSettingsState.suffixes(): List<String>`.
- Produces `MarkmapSuffixMatcher.matches(fileName: String): Boolean`.
- Produces Settings page under `Tools → Dev Workspace → MarkMap`.

- [ ] **Step 1: Write failing matcher tests**

Include:

```kotlin
@Test fun `default mm md suffix matches`() { ... }
@Test fun `default mm markdown suffix matches`() { ... }
@Test fun `matching ignores case`() { ... }
@Test fun `plain markdown does not match`() { ... }
@Test fun `xmm md does not cross suffix boundary`() { ... }
@Test fun `leading dots blanks and duplicates normalize`() { ... }
@Test fun `empty suffix list disables matching`() { ... }
```

- [ ] **Step 2: Implement normalization**

Use behavior equivalent to:

```kotlin
fun normalizeSuffixes(values: Iterable<String>): List<String> =
    values.asSequence()
        .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
```

- [ ] **Step 3: Implement boundary-safe matching**

Use behavior equivalent to:

```kotlin
fun matches(fileName: String): Boolean {
    val normalizedName = fileName.lowercase(Locale.ROOT)
    return suffixes.any { suffix ->
        normalizedName == suffix || normalizedName.endsWith(".$suffix")
    }
}
```

- [ ] **Step 4: Implement application settings**

Defaults:

```kotlin
val DEFAULT_SUFFIXES = listOf("mm.md", "mm.markdown")
```

Persist in `dev-workspace.xml` using a stable application-level component.

- [ ] **Step 5: Implement Settings UI**

Provide:

- multiline editor, one suffix per line;
- explanatory text;
- Apply normalization;
- Reset;
- Restore Defaults;
- immediate visibility on subsequent Action updates.

Reuse an existing Dev Workspace settings parent. If none exists, add a parent configurable with stable ID `com.anmi.devworkspace.settings`.

- [ ] **Step 6: Register the configurable and strings**

Do not hard-code visible Chinese text in production classes.

- [ ] **Step 7: Run verification**

```powershell
.\gradlew.bat test --tests "*MarkmapSuffixMatcher*" --tests "*MarkmapSettings*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/anmi/devworkspace/markmap src/main/resources/META-INF/plugin.xml src/main/resources/messages/DevWorkspaceBundle.properties src/test
git commit -m "feat: configure MarkMap filename suffixes"
```

**Checkpoint 1:** Report imported resources, absence of npm Gradle hooks, normalized default suffixes, settings location, tests, commits, and clean status.

---

### Task 3: Migrate Preview Panel and Project Service

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/MarkmapPreviewPanel.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/MarkmapPreviewService.kt`
- Modify bundle strings.
- Test: `src/test/kotlin/com/anmi/devworkspace/markmap/MarkmapPreviewServiceTest.kt`

**Interfaces:**
- `MarkmapPreviewPanel.render(markdown: String, optionsJson: String = "{}")`
- `MarkmapPreviewService.setPanel(panel)`
- `MarkmapPreviewService.clearPanel(panel)`
- `MarkmapPreviewService.preview(document)`

- [ ] **Step 1: Port classes into the package**

Add:

```kotlin
package com.anmi.devworkspace.markmap
```

Replace hard-coded user text with bundle messages.

- [ ] **Step 2: Preserve the WebView protocol**

Keep Base64 encoding and:

```javascript
window.updateMarkmapFromBase64(markdownBase64, optionsBase64)
```

Do not redesign the frontend protocol.

- [ ] **Step 3: Add safe panel lifecycle**

Provide an identity-safe method:

```kotlin
fun clearPanel(candidate: MarkmapPreviewPanel) {
    if (panel === candidate) panel = null
}
```

The panel or its owner must invoke this during disposal.

- [ ] **Step 4: Write deterministic service tests**

Test:

- late panel receives current Document;
- switching Document disposes old binding;
- repeated changes debounce to the last render;
- disposed service no longer renders;
- clearing a stale panel does not clear a newer panel.

Use controlled scheduling/test abstractions instead of `Thread.sleep`.

- [ ] **Step 5: Run verification and commit**

```powershell
.\gradlew.bat test --tests "*MarkmapPreviewService*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: migrate MarkMap preview runtime"
```

---

### Task 4: Add Tool Window, Action, Icons, and Plugin Registration

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/MarkmapIcons.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/MarkmapToolWindowFactory.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/markmap/OpenMarkmapPreviewAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/DevWorkspaceBundle.properties`
- Test: `src/test/kotlin/com/anmi/devworkspace/markmap/OpenMarkmapPreviewActionTest.kt`

**Interfaces:**
- Action ID: `com.anmi.devworkspace.markmap.openPreview`
- Tool Window ID: `Markmap`
- Uses `MarkmapSuffixMatcher` and `MarkmapPreviewService`.

- [ ] **Step 1: Inspect actual icon files**

Run:

```powershell
Get-ChildItem "src\main\resources\icons"
```

Choose the actual MarkMap SVG filename. Do not rename unrelated icons.

- [ ] **Step 2: Implement icon holder**

Example:

```kotlin
object MarkmapIcons {
    @JvmField
    val ToolWindow: Icon =
        IconLoader.getIcon("/icons/<actual-markmap-file>.svg", MarkmapIcons::class.java)
}
```

- [ ] **Step 3: Implement Tool Window factory**

Create panel, content, register the panel as the content disposer using the supported Build 262 API, add content, then attach the panel to the service.

- [ ] **Step 4: Write failing Action tests**

Test:

- `.mm.md` visible/enabled;
- `.mm.markdown` visible/enabled;
- ordinary `.md` hidden/disabled;
- configured custom suffix works;
- Action text is localized Chinese and contains no `%`;
- Action update reads filename only.

- [ ] **Step 5: Implement Action**

Use:

```kotlin
override fun getActionUpdateThread(): ActionUpdateThread =
    ActionUpdateThread.BGT
```

or the correct Build 262 thread after checking accessed DataKeys. Do not read Document text in `update()`.

- [ ] **Step 6: Register Tool Window and Action**

Merge only the required entries into the target `plugin.xml`.

Action groups:

```xml
<add-to-group group-id="EditorPopupMenu" anchor="last"/>
<add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
```

Do not add a default shortcut.

Use Action resource-bundle convention keys.

- [ ] **Step 7: Run verification**

```powershell
.\gradlew.bat test --tests "*OpenMarkmapPreviewAction*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/anmi/devworkspace/markmap src/main/resources/META-INF/plugin.xml src/main/resources/messages/DevWorkspaceBundle.properties src/test
git commit -m "feat: integrate MarkMap tool window and action"
```

**Checkpoint 2:** Report the actual icon used, Tool Window registration, Action resource keys, suffix visibility tests, JCEF disposal registration, build results, commits, and clean status.

---

### Task 5: Add Documentation and Migration Cleanup

**Files:**
- Modify: target `README.md`
- Create: `docs/markmap-integration.md`
- Modify: `docs/markmap-webview-development.md`
- Modify: any plugin manual checklist or feature overview already present.

**Interfaces:**
- Documents the unified plugin and MarkMap usage.
- Does not change runtime behavior.

- [ ] **Step 1: Document user behavior**

Include:

- default `.mm.md` / `.mm.markdown`;
- Settings path;
- context menu usage;
- no ordinary Markdown recognition by default;
- JCEF requirement;
- no default shortcut.

- [ ] **Step 2: Document developer behavior**

Include source project provenance, destination packages, WebView build, and why Gradle does not run npm.

- [ ] **Step 3: Record removed legacy items**

Explicitly record that the following were not migrated:

```text
MarkmapPreviewHolder.kt
old plugin.xml identity
old Gradle npm hook
old publishing metadata
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs
git commit -m "docs: document integrated MarkMap preview"
```

---

### Task 6: Final Integration Verification

**Files:**
- Modify only files required by verified failures.
- Create or update: `docs/markmap-manual-test-checklist.md`

**Interfaces:**
- Produces a manually testable unified Dev Workspace plugin.

- [ ] **Step 1: Run static source checks**

Confirm:

```powershell
Get-ChildItem "src\main\kotlin" -Recurse -File |
    Select-String -Pattern "package com\.anmi\.markmap|class MarkmapPreviewHolder"

Get-ChildItem -Recurse -File |
    Select-String -Pattern "buildMarkmapWebview"
```

Expected: no migrated old package, no Holder, no Gradle npm hook.

- [ ] **Step 2: Run clean verification**

Close any `runIde` sandbox first, then:

```powershell
.\gradlew.bat clean test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git diff --check
git status --short
```

- [ ] **Step 3: Prepare manual checklist**

Include:

1. ordinary Markdown hidden;
2. `.mm.md` and `.mm.markdown` visible;
3. editor and Project View actions;
4. Tool Window icon;
5. live 300ms refresh;
6. custom suffix immediate effect;
7. suffix removal immediate effect;
8. restore defaults;
9. JCEF unsupported message;
10. project close/disposal;
11. Dev Tasks and Dev Library regression;
12. build without Node.

- [ ] **Step 4: Commit final fixes/checklist**

```bash
git add src docs
git commit -m "test: verify integrated MarkMap preview"
```

Skip an empty commit if no source or documentation change remains.

**Checkpoint 3:** Stop before deleting or archiving the source project. Report all commits, source files migrated, legacy files intentionally omitted, suffix behavior, Settings behavior, WebView packaging, absence of npm Gradle hooks, test/build/boundary results, remaining warnings, and clean Git status.
