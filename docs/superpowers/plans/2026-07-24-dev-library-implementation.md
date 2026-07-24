# Dev Workspace 0.2 Dev Library Implementation Plan

> **For agentic workers:** Execute one numbered task at a time. Use test-first development for pure Kotlin logic, review the diff after every task, and commit each task independently.

**Goal:** Add a three-scope bookmark and knowledge library to Dev Workspace with JSON/Markdown storage, search/filtering, previews, smart opening, file health checks, and ZIP import/export.

**Architecture:** Reuse the task runner’s repository, atomic-write, last-valid-snapshot, coroutine, localization, and Tool Window conventions without coupling library models to task models. Three independent repositories publish snapshots that are merged for presentation without override semantics.

**Tech Stack:** Kotlin, IntelliJ IDEA 2026.2 Build 262, Java 25, kotlinx.serialization JSON or the project’s approved JSON library, IntelliJ Platform UI APIs, Markdown renderer already available in the platform when suitable.

## Global Constraints

- Repository: `D:\idea-workspace\java\idea-dev-workspace\Dev Workspace`
- Work on the current feature branch unless the user creates a new one.
- Root package: `com.anmi.devworkspace.library`
- UI text must be Simplified Chinese and stored in `DevWorkspaceBundle.properties`.
- Files/images/media are referenced by path only; never copied into the library.
- External files must never be deleted by the plugin.
- Three scopes merge; they do not override each other.
- Keep direct IntelliJ API adapters separate from pure domain/search/storage logic.
- Never block EDT with JSON IO, Markdown IO, file checks, image decoding, ZIP IO, or cache work.
- Add useful comments to public interfaces and non-obvious logic; do not add mechanical comments.
- Do not start Task 15 from the old task-runner plan.
- Do not reintroduce task stop/process-management behavior.
- Before each commit run focused tests, full `test`, and `buildPlugin`.
- No Marketplace publishing.

---

### Task 1: Add Library Domain Models and JSON Schema

**Files:**
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryScope.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItemType.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryGroup.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/domain/LibraryItem.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryDocument.kt`
- Create: `src/main/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonCodec.kt`
- Test: `src/test/kotlin/com/anmi/devworkspace/library/storage/LibraryJsonCodecTest.kt`

**Deliverable:** Stable version-1 JSON round trip with deterministic ordering and validation.

**Required tests:**
- empty document round trip;
- all item types round trip;
- stable JSON ordering;
- duplicate group/item IDs rejected;
- unknown version rejected;
- tags trimmed and deduplicated;
- Markdown item requires `contentFile`;
- non-Markdown item requires `target`.

**Commit:** `feat: add library domain and JSON schema`

---

### Task 2: Add Three Repositories, Markdown Bodies, and Atomic Persistence

**Files:**
- Create: `library/storage/LibraryRepository.kt`
- Create: `library/storage/FileLibraryRepository.kt`
- Create: `library/storage/LibraryRepositoryPaths.kt`
- Create: `library/storage/LibrarySnapshot.kt`
- Create: `library/storage/LibraryLastValidStore.kt`
- Create: `library/storage/MarkdownContentStore.kt`
- Test: repository/path/content tests.

**Deliverable:** Independent GLOBAL, PROJECT_SHARED, PROJECT_PRIVATE repositories.

**Requirements:**
- exact paths from design;
- missing repository is valid empty state;
- atomic JSON write;
- Markdown body written to `contents/<id>.md`;
- failed write leaves old data intact;
- one layer corruption falls back to its own last-valid snapshot;
- deleting Markdown item deletes only internal Markdown file;
- no external target deletion;
- disk work on IO dispatcher;
- comments explain transaction order and recovery behavior.

**Commit:** `feat: persist library scopes and markdown content`

---

### Task 3: Add Merged Library Service and Hot Reload

**Files:**
- Create: `library/service/LibraryService.kt`
- Create: `library/service/LibraryState.kt`
- Create: `library/storage/LibraryFileListener.kt`
- Test: `LibraryServiceTest.kt`

**Deliverable:** `StateFlow<LibraryState>` combining three repositories without overrides.

**Requirements:**
- retain scope on every item/group;
- same IDs and titles across scopes may coexist;
- group visual aggregation uses normalized group name only in presentation;
- 400ms debounced VFS reload;
- no timing sleeps in tests;
- internal writes do not loop;
- repaired layer clears error automatically;
- disposal cancels collectors and debounce jobs.

**Commit:** `feat: merge and hot reload library scopes`

**Checkpoint 1:** Pause and report schema, paths, tests, commits, and clean status.

---

### Task 4: Add Type Detection, Search, Filters, and Sorting

**Files:**
- Create: `library/service/LibraryTypeDetector.kt`
- Create: `library/search/LibraryQuery.kt`
- Create: `library/search/LibrarySearchEngine.kt`
- Create: `library/search/LibrarySort.kt`
- Test: detector/search tests.

**Deliverable:** Pure Kotlin query engine.

**Requirements:**
- automatic type detection with manual override support;
- search title, Markdown body, URL/path, tags, note, group;
- case-insensitive text;
- exact tag matching;
- dimensions combine with AND;
- selections inside a dimension combine with OR;
- favorite/path-state filters;
- default favorite/group/update sort;
- alternate title/create/update/type sort;
- deterministic tie-breaking;
- comments describe boolean combination rules.

**Commit:** `feat: search and filter library entries`

---

### Task 5: Add Path Health and Smart Opening

**Files:**
- Create: `library/files/LibraryPathResolver.kt`
- Create: `library/files/LibraryFileStatusService.kt`
- Create: `library/open/LibraryItemOpener.kt`
- Create: `library/open/IdeaLibraryItemOpener.kt`
- Test: path/status/decision tests.

**Deliverable:** File existence state, relocation support, and open decision engine.

**Requirements:**
- `${PROJECT_DIR}` conversion;
- forward-slash persistence;
- check on load, manual refresh, and open;
- missing item retained;
- relocation changes target only;
- link → browser;
- platform-readable file → IDEA editor;
- image → preview/optional IDEA/system;
- unsupported/media → system;
- missing target never opened;
- pure decision code tested without desktop/browser launch;
- IntelliJ calls isolated and commented.

**Commit:** `feat: validate and intelligently open library targets`

---

### Task 6: Add Markdown and Image Preview Services

**Files:**
- Create: `library/preview/MarkdownPreviewService.kt`
- Create: `library/preview/ImagePreviewService.kt`
- Create: `library/preview/ImagePreviewCache.kt`
- Test: cache and cancellation tests.

**Deliverable:** Safe asynchronous previews.

**Requirements:**
- Markdown source and rendered preview;
- no script execution;
- image decode off EDT;
- bounded cache;
- cache key path + mtime + requested size;
- proportional scaling;
- avoid retaining original huge image;
- stale cache invalidation;
- missing/corrupt image placeholder;
- cancellation and disposal release resources;
- comments explain cache limits.

**Commit:** `feat: preview markdown and images`

---

### Task 7: Add ZIP Import and Export

**Files:**
- Create: `library/transfer/LibraryArchiveManifest.kt`
- Create: `library/transfer/LibraryExporter.kt`
- Create: `library/transfer/LibraryImporter.kt`
- Test: archive tests.

**Deliverable:** Versioned ZIP containing manifest, JSON, and Markdown bodies only.

**Requirements:**
- no external file bytes;
- validate archive before modifying repository;
- choose target scope;
- ID conflict generates UUID;
- same-name group reused;
- missing Markdown body skipped and reported;
- safe ZIP entry validation against path traversal;
- temporary staging and all-or-nothing repository update;
- comments explain security and transaction boundary.

**Commit:** `feat: import and export library archives`

**Checkpoint 2:** Pause and report archive format, security tests, commits, and clean status.

---

### Task 8: Build the Library Tool Window Shell

**Files:**
- Create: `library/ui/LibraryToolWindowFactory.kt`
- Create: `library/ui/LibraryPanel.kt`
- Create: `library/ui/LibraryListModel.kt`
- Create: `library/ui/LibraryItemRenderer.kt`
- Create: `library/ui/LibraryFilterPanel.kt`
- Modify: `plugin.xml`
- Modify: `DevWorkspaceBundle.properties`

**Deliverable:** Chinese three-pane Tool Window.

**Requirements:**
- left group/filter pane;
- center list;
- right collapsible detail pane;
- top search;
- toolbar actions;
- empty state;
- responsive splitters;
- no hard-coded user strings;
- platform icons/colors;
- StateFlow collected safely onto EDT;
- no secrets/task runtime coupling.
- register configurable `Open Library` and `Quick Add to Library` actions in Find Action and Keymap;
- `Open Library` activates the Tool Window, focuses search, and preserves filters/group/selection;
- inspect the Build 262 Windows default keymap before assigning defaults; leave unbound when safety is unclear;
- add Action System shell and context-extraction boundary without implementing persistence prematurely;
- keep every action string in `DevWorkspaceBundle.properties`.

**Commit:** `feat: add library tool window`

---

### Task 9: Add Editors, Groups, Tags, Favorites, and Relocation

**Files:**
- Create: `library/ui/LibraryItemDialog.kt`
- Create: `library/ui/LibraryItemEditorModel.kt`
- Create: `library/ui/LibraryGroupDialog.kt`
- Create: `library/ui/LibraryTagEditor.kt`
- Create: `library/ui/LibraryRelocateDialog.kt`
- Modify: `LibraryPanel.kt`
- Test: editor-model tests.

**Deliverable:** Complete CRUD.

**Requirements:**
- auto-detect type, manual correction;
- Markdown source editor;
- file chooser;
- group and tag editing;
- favorite toggle;
- copy;
- move/copy scope;
- delete safety;
- group deletion reassignment;
- validation refresh;
- scrollable dialogs;
- path status visible;
- comments on scope-move transaction.
- implement UI-independent quick-add drafts and DataContext extraction;
- editor selections become Markdown, with fenced code and inferred language for code files;
- include project-relative source path and selected line range;
- current editor and Project View files default to project-private; context-free clipboard defaults global;
- project-shared requires explicit selection and previous scope is never blindly reused;
- remember last group/tags per project;
- support one batch confirmation dialog for multiple Project View files, without directory recursion;
- add editor and Project View context-menu actions with correct `update()` and `ActionUpdateThread`;
- lightweight confirmation supports Enter, Escape, and “完整编辑” with the same draft.

**Commit:** `feat: manage library entries and groups`

---

### Task 10: Wire Preview, Smart Open, Import/Export, and Final Polish

**Files:**
- Modify relevant UI/service files;
- Create: `docs/library-configuration.md`
- Create: `docs/library-manual-test-checklist.md`

**Deliverable:** End-to-end 0.2 module.

**Manual scenarios:**
1. create Markdown, link, file, image, media;
2. auto type and manual override;
3. all three scopes merge;
4. same-name groups aggregate visually;
5. search/filter/tag/favorite;
6. image and Markdown preview;
7. IDEA/system/browser opening;
8. missing file warning and relocation;
9. copy/move/delete safety;
10. external JSON/Markdown reload;
11. corrupt one scope while other scopes remain;
12. ZIP export/import and ID conflict;
13. Chinese UI and scrollability;
14. project disposal without leaks.
15. Open Library preserves view state and focuses search;
16. quick-add editor selection/current file/Project View/clipboard contexts;
17. multi-file batch review without recursive directories;
18. successful quick add notification and “查看条目” navigation;
19. external Markdown edits refresh both search body and visible preview without JSON changes;
20. both actions appear in Find Action and Keymap without overriding a conflicting shortcut.

**Action-system completion rules:**
- wire quick-add persistence off EDT and do not force-open the Tool Window after save;
- wire “完整编辑” to the full editor with the unchanged draft;
- keep context extraction independent from persistence and dialogs;
- remote synchronization remains out of scope; do not implement WebDAV, GitHub, custom-server, or `LibrarySyncProvider` behavior.

**Verification:**
```powershell
.\gradlew.bat test
.\gradlew.bat buildPlugin
powershell -ExecutionPolicy Bypass -File scripts/check-terminal-api-boundary.ps1
```

**Commit:** `feat: complete Dev Library module`

**Checkpoint 3:** Pause before any new module. Report all commits, tests, manual verification, reviewer findings, and clean Git status.
