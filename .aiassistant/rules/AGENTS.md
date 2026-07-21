---
应用: 始终
---

# Dev Workspace Agent Instructions

Source of truth:

- Design:
  `docs/superpowers/specs/2026-07-21-dev-workspace-task-runner-design.md`
- Implementation plan:
  `docs/superpowers/plans/2026-07-21-dev-workspace-task-runner-implementation.md`

Rules:

- Work on the current feature branch; no separate worktree is required.
- Execute one numbered plan task at a time.
- Read only the current task and referenced interfaces.
- Run `gradlew.bat test` and `gradlew.bat buildPlugin` before each commit.
- Keep direct experimental Terminal imports under `terminal/idea262`.
- Never expose secrets.
- Stop on verification failure.
- Pause after Tasks 7, 9, 14, 17, and 19.
