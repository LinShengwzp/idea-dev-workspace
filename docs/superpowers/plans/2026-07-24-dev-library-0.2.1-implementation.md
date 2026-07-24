# Dev Workspace 0.2.1 Dev Library Acceptance Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the first-round Dev Library acceptance issues: startup crash, broken Action localization, missing refresh behavior, weak filter/list/detail hierarchy, lack of structured source navigation, and poor path/tag editing ergonomics.

**Architecture:** Keep the existing three-scope repository and service architecture. Upgrade the persisted schema from v1 to v2 by adding an optional structured `source` object, then layer UI improvements on top of existing search, preview, transfer, and mutation services. Keep pure logic testable outside IntelliJ UI and isolate IntelliJ API adapters.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2 Build 262, Java 25, Gradle, existing JSON codec, IntelliJ Action System, Swing/IntelliJ UI components, coroutines.

## Global Constraints

- Repository: `D:\idea-workspace\java\idea-dev-workspace\Dev Workspace`
- Base branch contains commits through `8936d2d feat: complete Dev Library module`.
- Root package: `com.anmi.devworkspace.library`
- UI strings must come from `DevWorkspaceBundle.properties`.
- Existing three-scope merge semantics remain unchanged.
- Existing external files, images, and media remain path references only.
- External files must never be copied or deleted.
- Disk, ZIP, Markdown, image, and status refresh work must stay off EDT.
- Existing import/export format must remain backward-compatible.
- JSON v1 must load; subsequent saves must write JSON v2.
- Do not implement WebDAV, Git/GitHub sync, or a custom server.
- Do not bind default shortcuts in this version.
- Do not bundle unrelated deprecation cleanup.
- Add useful comments around version migration, source navigation, Action localization, refresh invalidation, and tag editor behavior.
- Run focused tests, full `test`, `buildPlugin`, and Terminal API boundary checks before each commit.
- Commit every numbered task separately.
- Pause after Tasks 2, 5, and 8.

---

## File Map

### Existing files likely modified

- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt`
  - Fix initialization order.
  - Add refresh action.
  - Remove toolbar relocation action.
  - Render richer details and clickable targets/sources.
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryFilterPanel.kt`
  - Add clear-all, per-dimension “全部”, group and ungrouped filters, visual hierarchy.
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryItemRenderer.kt`
  - Add type/status icons and multi-line metadata.
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryEditorDialog.kt`
  - Add path chooser and tag-chip editor.
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryQuickAddDialog.kt`
  - Display structured source and preserve it into full editor.
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryActionContextExtractor.kt`
  - Capture structured editor source and correct clipboard precedence.
- `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryDocument.kt`
  - Upgrade schema representation to version 2.
- `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonCodec.kt`
  - Read v1/v2 and write v2.
- `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItem.kt`
  - Add optional `source`.
- `src/main/kotlin/com/anmi/devworkspace/library/open/IdeaLibraryItemOpener.kt`
  - Add source navigation and missing/stale line handling.
- `src/main/kotlin/com/anmi/devworkspace/library/service/LibraryService.kt`
  - Expose complete manual refresh.
- `src/main/resources/META-INF/plugin.xml`
  - Correct Action resource-bundle wiring.
- `src/main/resources/messages/DevWorkspaceBundle.properties`
  - Add Action convention keys and new UI strings.

### New focused files

- `src/main/kotlin/com/anmi/devworkspace/library/domain/LibrarySourceKind.kt`
- `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItemSource.kt`
- `src/main/kotlin/com/anmi/devworkspace/library/open/LibrarySourceNavigator.kt`
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryTagChipEditor.kt`
- `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryDetailsModel.kt`
- `src/test/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonV2MigrationTest.kt`
- `src/test/kotlin/com/anmi/devworkspace/library/open/LibrarySourceNavigatorTest.kt`
- `src/test/kotlin/com/anmi/devworkspace/library/ui/LibraryFilterStateTest.kt`
- `src/test/kotlin/com/anmi/devworkspace/library/ui/LibraryTagChipEditorModelTest.kt`
- `src/test/kotlin/com/anmi/devworkspace/library/ui/LibraryDetailsModelTest.kt`

---

### Task 1: Fix Startup Crash and Action Localization

**Files:**
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/DevWorkspaceBundle.properties`
- Test: nearest existing Tool Window / action registration tests.

**Interfaces:**
- Consumes: existing `LibraryPanel`, registered action IDs, and `DevWorkspaceBundle`.
- Produces: stable `LibraryPanel` initialization and correctly localized Action presentations.

- [ ] **Step 1: Write or extend a regression test for panel construction**

Create the smallest platform test that constructs `LibraryPanel` or triggers `LibraryToolWindowFactory.createToolWindowContent()` and asserts no exception is thrown.

Expected pre-fix failure: `NullPointerException` from `contextualActions += actions`.

- [ ] **Step 2: Run the focused test and verify it fails**

```powershell
.\gradlew.bat test --tests "*LibraryPanel*"
```

Expected: failure reproduces the initialization crash, or document why the nearest platform test cannot instantiate the panel.

- [ ] **Step 3: Move `contextualActions` before `init`**

In `LibraryPanel.kt`, place:

```kotlin
private val contextualActions = mutableListOf<PanelAction>()
```

before the `filterPanel` property and before the `init` block. Remove the old declaration below `createMainContent()`.

Do not make it nullable and do not add null guards.

- [ ] **Step 4: Correct Action localization wiring**

In `plugin.xml`, ensure the `<actions>` element references the bundle:

```xml
<actions resource-bundle="messages.DevWorkspaceBundle">
```

Remove literal `text="%library...."` values from the library actions.

For each registered action ID, add convention-based keys:

```properties
action.<FULL_ACTION_ID>.text=...
action.<FULL_ACTION_ID>.description=...
```

Cover:

- open library;
- quick add;
- add selected editor content;
- add current file;
- add Project View files.

- [ ] **Step 5: Add a focused action localization test**

Load each action through `ActionManager` and assert:

- text is nonblank;
- text does not start with `%`;
- text is the expected Chinese label.

- [ ] **Step 6: Run verification**

```powershell
.\gradlew.bat test --tests "*LibraryPanel*" --tests "*Library*Action*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt src/main/resources/META-INF/plugin.xml src/main/resources/messages/DevWorkspaceBundle.properties src/test
git commit -m "fix: stabilize library startup and action localization"
```

---

### Task 2: Upgrade JSON Schema to v2 and Add Structured Source

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibrarySourceKind.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItemSource.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItem.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryDocument.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonCodec.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonV2MigrationTest.kt`

**Interfaces:**
- Produces:
  - `LibrarySourceKind.EDITOR_SELECTION`
  - `LibraryItemSource(kind, path, startLine, endLine)`
  - `LibraryItem.source: LibraryItemSource?`
  - decoder accepting v1 and v2;
  - encoder always writing v2.

- [ ] **Step 1: Write failing migration tests**

Cover:

```kotlin
@Test
fun `version 1 item loads with null source`() { ... }

@Test
fun `version 2 source round trips`() { ... }

@Test
fun `save always writes version 2`() { ... }

@Test
fun `invalid partial line range is rejected`() { ... }

@Test
fun `invalid reversed line range is rejected`() { ... }
```

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
.\gradlew.bat test --tests "*LibraryJsonV2MigrationTest"
```

Expected: missing source model / version handling failures.

- [ ] **Step 3: Add source model**

```kotlin
enum class LibrarySourceKind {
    EDITOR_SELECTION,
}

data class LibraryItemSource(
    val kind: LibrarySourceKind,
    val path: String,
    val startLine: Int?,
    val endLine: Int?,
) {
    init {
        require((startLine == null) == (endLine == null)) {
            "Source line range must be either fully absent or fully present"
        }
        if (startLine != null && endLine != null) {
            require(startLine >= 1) { "Source startLine must be at least 1" }
            require(endLine >= startLine) { "Source endLine must not precede startLine" }
        }
    }
}
```

Add `source: LibraryItemSource? = null` to `LibraryItem`.

- [ ] **Step 4: Implement v1/v2 codec behavior**

Requirements:

- decode version 1 with `source = null`;
- decode version 2 with optional source;
- reject other versions;
- encode only version 2;
- do not serialize repository scope;
- preserve deterministic ordering.

- [ ] **Step 5: Update all existing test fixtures and constructors**

Prefer default `source = null` to minimize churn. Do not alter IDs, timestamps, or content file names.

- [ ] **Step 6: Run verification**

```powershell
.\gradlew.bat test --tests "*LibraryJson*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/anmi/devworkspace/library/domain src/main/kotlin/com/anmi/devworkspace/library/storage src/test/kotlin/com/anmi/devworkspace/library/storage
git commit -m "feat: add structured library item sources"
```

**Checkpoint 1:** Pause and report startup fix, Action labels, schema migration behavior, tests, commits, and clean status.

---

### Task 3: Capture Editor Selection Source and Fix Quick-Add Precedence

**Files:**
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryActionContextExtractor.kt`
- Modify: quick-add draft/editor state models.
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryQuickAddDialog.kt`
- Test: existing context extractor tests plus new source assertions.

**Interfaces:**
- Consumes: `LibraryItemSource`.
- Produces: quick-add drafts containing optional structured source.

- [ ] **Step 1: Add failing context tests**

Cover:

- editor selection produces `source.path/startLine/endLine`;
- source lines are 1-based;
- project-local path is persisted with `${PROJECT_DIR}`;
- selected code still produces fenced Markdown with inferred language;
- focused Project View selection wins over clipboard;
- focused editor file wins over clipboard;
- unfocused open editor does not suppress clipboard URL;
- clipboard URL defaults to GLOBAL.

- [ ] **Step 2: Implement explicit context precedence**

Use:

```text
explicit editor selection
→ explicit Project View file selection
→ currently focused editor file
→ clipboard URL/text
```

Do not treat any merely open editor as active context.

- [ ] **Step 3: Preserve source through quick and full editors**

The lightweight dialog must display:

```text
来源：${PROJECT_DIR}/... · 第 x–y 行
```

“完整编辑” must preserve the draft source unchanged.

- [ ] **Step 4: Run verification and commit**

```powershell
.\gradlew.bat test --tests "*Library*Context*" --tests "*QuickAdd*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: capture structured source from editor selections"
```

---

### Task 4: Add Complete Manual Refresh and Path-State Refresh

**Files:**
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/service/LibraryService.kt`
- Modify: preview/cache services as needed.
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt`
- Test: service refresh and selection preservation tests.

**Interfaces:**
- Produces a `suspend fun refresh()` or equivalent explicit API that:
  - reloads all scopes;
  - rereads Markdown;
  - invalidates image/preview caches;
  - recalculates path/source state.

- [ ] **Step 1: Write failing refresh tests**

Cover:

- external file deletion becomes missing after refresh;
- external Markdown modification appears after refresh;
- refresh does not mutate data;
- current query/filter state remains unchanged;
- selected item key is restored when still present.

- [ ] **Step 2: Implement complete refresh**

The toolbar refresh action must launch off EDT and then render on EDT.

Remove the toolbar relocate action.

- [ ] **Step 3: Add refresh UI strings and icon**

Use a standard platform refresh icon and localized tooltip.

- [ ] **Step 4: Run verification and commit**

```powershell
.\gradlew.bat test --tests "*Library*Refresh*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: refresh library state and external file status"
```

---

### Task 5: Rebuild Filters and Add Group Aggregation

**Files:**
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryFilterPanel.kt`
- Modify: filter state/query model if required.
- Test: `src/test/kotlin/com/anmi/devworkspace/library/ui/LibraryFilterStateTest.kt`

**Interfaces:**
- Produces:
  - clear-all;
  - per-dimension clear;
  - group-name aggregation;
  - ungrouped filtering;
  - explicit visual selection state.

- [ ] **Step 1: Add failing pure state tests**

Cover:

- “全部资料” resets text and all dimensions;
- “全部分组” clears only group;
- “全部作用域” clears only scope;
- “全部类型” clears only type;
- “全部标签” clears only tags;
- “全部状态” clears only path state;
- same-name groups from different scopes match one visual group;
- ungrouped items match “未分组”;
- AND/OR semantics remain unchanged.

- [ ] **Step 2: Implement structured sections**

Use clear separators and platform selection styling. Ensure deep-theme contrast.

Recommended sections:

```text
全部资料
分组
作用域
类型
标签
状态
```

- [ ] **Step 3: Add item counts where practical**

Counts may be omitted if they create excessive complexity, but the visual hierarchy and “全部” behavior are mandatory.

- [ ] **Step 4: Run verification and commit**

```powershell
.\gradlew.bat test --tests "*LibraryFilter*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: improve library grouping and filters"
```

**Checkpoint 2:** Pause and report refresh behavior, filter reset semantics, group aggregation, tests, commits, and clean status.

---

### Task 6: Improve List Rendering, Icons, and Toolbar Semantics

**Files:**
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryItemRenderer.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt`
- Add a small icon resolver if needed.

**Interfaces:**
- Produces distinct visual treatment for item type, status, favorite, metadata, and summary.

- [ ] **Step 1: Add renderer-model tests**

Prefer a pure presentation model that returns:

- title;
- icon key;
- metadata line;
- summary;
- warning state;
- favorite state.

- [ ] **Step 2: Implement type icons**

Requirements:

- Markdown: document/Markdown-like icon;
- Link: web/link icon;
- File: file-type icon when resolvable;
- Image: image icon;
- Media: media/play icon;
- Missing path/source: warning overlay or visible status.

- [ ] **Step 3: Adjust toolbar icons**

- group management: list/group/task-list-style icon;
- open: existing open icon;
- refresh: refresh icon;
- no relocation action.

- [ ] **Step 4: Run full verification and commit**

```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: clarify library list and toolbar visuals"
```

---

### Task 7: Add Tag Chips and Path Chooser to the Editor

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryTagChipEditor.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryEditorDialog.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/library/ui/LibraryTagChipEditorModelTest.kt`

**Interfaces:**
- Produces a stable `Set<String>` for editor state.
- Adds a file chooser for FILE/IMAGE/MEDIA target editing.

- [ ] **Step 1: Add pure tag parsing tests**

Cover:

- Enter creates one tag;
- commas, Chinese commas, and semicolons split tags;
- paste creates multiple tags;
- trim and deduplicate;
- empty tags ignored;
- Backspace with empty input removes last tag;
- explicit remove deletes selected tag.

- [ ] **Step 2: Implement theme-safe Tag Chip UI**

Use stable Swing/IntelliJ components only. Avoid internal tag APIs.

- [ ] **Step 3: Add target chooser**

Show chooser button only for FILE/IMAGE/MEDIA. Persist selected path through `LibraryPathResolver.persist()`.

- [ ] **Step 4: Preserve structured source**

The source is read-only in the normal editor for this version; editing `target` must not erase `source`.

- [ ] **Step 5: Run verification and commit**

```powershell
.\gradlew.bat test --tests "*LibraryTagChip*"
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git add src/main src/test
git commit -m "feat: improve library tag and path editing"
```

---

### Task 8: Add Rich Details and Clickable Source Navigation

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryDetailsModel.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/open/LibrarySourceNavigator.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/open/IdeaLibraryItemOpener.kt`
- Modify: `src/main/kotlin/com/anmi/devworkspace/library/ui/LibraryPanel.kt`
- Test:
  - `LibraryDetailsModelTest.kt`
  - `LibrarySourceNavigatorTest.kt`

**Interfaces:**
- `LibraryDetailsModel` exposes title, type, scope, group, tags, target/source, status, note, timestamps, preview mode.
- `LibrarySourceNavigator` resolves and navigates to a valid source range.

- [ ] **Step 1: Add failing details-model tests**

Cover all item types, ungrouped state, tags, target, source, missing status, timestamps.

- [ ] **Step 2: Add failing source navigation decision tests**

Cover:

- valid range;
- missing file;
- start line beyond EOF;
- end line beyond EOF;
- no project context.

Keep file/line decision logic separate from editor side effects.

- [ ] **Step 3: Implement IntelliJ navigation adapter**

Open the file in IDEA, move caret to start line, select/highlight the available range, and scroll to center/visible.

- [ ] **Step 4: Build richer details UI**

Show:

- title;
- type/scope/group;
- tag chips;
- clickable URL/path/source;
- status;
- note;
- preview;
- creation/update time.

Use platform link styling and hand cursor. Add copy action for URL/path/source.

- [ ] **Step 5: Harden open behavior**

Every target/source click rechecks existence. Missing files produce a localized warning, never an uncaught exception.

- [ ] **Step 6: Update docs and manual checklist**

Modify:

- `docs/library-configuration.md`
- `docs/library-manual-test-checklist.md`
- `docs/superpowers/specs/2026-07-24-dev-library-0.2.1-acceptance-fixes-design.md`

Document JSON v2 migration and source behavior.

- [ ] **Step 7: Final verification**

```powershell
.\gradlew.bat clean test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
git status --short
```

Expected: all pass and worktree contains only intended Task 8 changes before commit.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test docs
git commit -m "feat: add rich library details and source navigation"
```

**Checkpoint 3:** Pause before any synchronization work. Report:

- all eight commits;
- JSON v1→v2 migration result;
- Action label verification;
- manual refresh behavior;
- filter and group behavior;
- Tag Chip behavior;
- source navigation edge cases;
- full test/build/boundary results;
- deprecation warnings;
- clean Git status.

---

## Manual Acceptance Checklist

1. Opening the Library no longer crashes.
2. Tools, Find Action, Keymap, editor context menu, and Project View menu show Chinese labels.
3. Existing v1 repositories load and save back as v2.
4. “全部资料” restores the complete view.
5. Each filter section has a working “全部”.
6. Group names appear in filters; same-name groups aggregate visually.
7. Item types are visually distinguishable.
8. Refresh immediately detects externally deleted/moved files.
9. The editor path chooser can replace a missing target.
10. Tags can be entered without repeatedly switching input method punctuation.
11. Details show type, scope, group, tags, status, note, target/source, and timestamps.
12. URLs and paths look and behave like links.
13. Editor-selection records open the original file and navigate to the recorded range.
14. Missing source files and stale lines show warnings without uncaught exceptions.
15. Import/export remains functional.
16. No external user file is copied or deleted.
17. `test`, `buildPlugin`, and Terminal API boundary checks pass.
