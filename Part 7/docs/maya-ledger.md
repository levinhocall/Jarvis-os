# Maya Phase Ledger

## Phase 0 — Capability audit + smoke-test harness

- **Branch:** `phase0/capability-audit`
- **Base:** `fix/a11y-forge-lia`
- **Status:** IN PROGRESS — source audit documentation added; diagnostic harness/build/device checks not yet verified.
- **Added:** `Part 7/docs/maya-capability-matrix.md`
- **Changed:** none of the existing runtime files in the documentation-only commit.
- **Status by capability:** see the capability matrix; statuses are source-inspection findings, not device-test results.
- **UNVERIFIED:** Gradle build/tests; overlay on top of other apps; intent launch paths; MediaProjection frame result; Gemini text/image calls; TTS/STT; accessibility enabled/connected on device.
- **Known gaps:** the dedicated smoke-test UI and copy-report action still need implementation and build validation. Phase 1+ work is out of scope.
- **FUTURE:** Phase 1 voice action router; Phase 2 avatar overlay; Phase 3 reactions; Phase 4 screen companion; Phase 5 whiteboard; Phase 6 accessibility actions gated on Doctor evidence; Phase 7 polish.
