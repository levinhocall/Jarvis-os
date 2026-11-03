# More Directives implementation and acceptance evidence

This extends the existing Kotlin Views/ViewBinding Android app (`com.jarvis.assistant`, target SDK 36). It does not replace it with Compose or change existing provider/service architecture.

## Implementation checklist

- [x] Canonical nine-tile registry shared by the main deck and feature hub; native cards and dedicated activities.
- [x] Voice Mode: native audio-reactive orb, immersive window, shared live-service state/transcripts, interrupt, mute and stop controls. Uses the existing Gemini Live microphone/service pipeline.
- [x] Neural Lens: opt-in CameraX latest-frame analysis and ML Kit streaming object tracking/classification; bounded scan frequency; camera/analyzer lifecycle cleanup. Existing still capture, gallery and Gemini analysis retained.
- [x] AMOLED Map: black canvas renders actual OSRM route, GPS and destination vectors; cached geometry for gestures; optional existing OSM raster view. Raster networking/animations disabled in vector mode.
- [x] Aura Control: existing appearance controls plus real battery/memory telemetry and app-level scan/background-review power policy.
- [x] Notifications: RecyclerView diffing, search/unread filtering, opt-in encrypted archive, credential filtering and exclusion of ongoing/group-summary/own-app noise for new archived entries.
- [x] Home Theme: persisted System / Light / Dark cycle using the existing theme manager and AppCompat configuration handling.
- [x] Mission Mode: explicit UI Agent / Core LLM selection with enforced tool allowlists, route persisted through approval/resume, exact-action approval, pause/resume, cancellation, unknown-outcome acknowledgement and stored history. UI inspection excludes Jarvis private screens and protected/password contexts.
- [x] My Ideas: encrypted Room/SQLCipher local records, explicit save/delete, on-device-only dictation when available, separate AI refinements, paged list. Optional WorkManager review remains OFF until consent; only ideas are read, not the personal-memory table.
- [x] English Teacher: four persona prompts, six levels, six goals, actual text/audio assessment, local encrypted profile/progress, streak/overall/fluency/pronunciation metrics and offline reply speech. Text never generates pronunciation scores.
- [x] Room 6->7 additive migration; existing memory/mission/notification/sync tables retained.

## Verification

Host migration check: `python3 scripts/verify-directive-migration.py` (from Part 7). It executes the migration SQL from MemoryDatabase, compares columns/indexes against the exported Room 7 schema, and verifies seeded rows survive in all six previous tables. This checks SQLite schema/data preservation, not Android Keystore/SQLCipher operation on a phone.

New JVM tests cover trainer/level/goal choices, streak dates, empty/invalid assessment handling, audio-only pronunciation, route persistence and fail-closed tool rejection, consequential UI click approval, notification noise filtering, theme cycling and power policy.

Final Gradle and GitHub CI outcomes are recorded after execution in the delivery report. No device/emulator results are claimed.

## Remaining physical-device acceptance

- [ ] Android 16 install and existing encrypted database upgrade using the SAME signing key; reboot and offline reopening of memories/ideas/progress.
- [ ] TalkBack, large font, narrow displays, display cutouts, gesture navigation, all nine tile targets and global theme recreation.
- [ ] Measure cold/warm opening and frame timing with a profiler. Zero latency and zero stutter are not established by compilation or unit tests.
- [ ] Gemini Live connection, audio reactivity, speech interruption/barge-in, mic denial, background/foreground and concurrent microphone use.
- [ ] ML Kit first-use model download, offline subsequent scans, camera denial, rotation/background/gallery transitions and thermal/battery behavior.
- [ ] Real GPS/routes and raster/vector toggle; test route service failures and map gestures.
- [ ] Accessibility-supported third-party app mission with explicit approval; cancellation, process death and uncertain-result recovery. Booking support depends on the real provider/app; no reservation is reported merely because a reminder was created.
- [ ] Idea save during provider outage; device-only dictation availability; opt-in review, cancellation and system Battery Saver.
- [ ] Four teachers with live provider credentials; actual audio samples, interruptions, offline TTS initialization and longitudinal score/streak display.

## Deliberate boundaries

- ML Kit gives tracked objects and broad supported categories, not arbitrary detailed object identity. Its unbundled model may need a first-use download.
- The black map is a route/GPS vector guide, not a complete downloadable vector street-map database. OSM raster remains optional. Destination lookup and routing need their existing remote services.
- Theme changes may recreate activities through Android/AppCompat. UI and network timing require measurement; no "zero redraw" guarantee is made.
- Power controls govern Jarvis scan frequency and optional idea review, not Android CPU scheduling or other applications.
- English scores are provider estimates, not certified exam results. All submitted learning text/audio is explicitly sent to the configured Gemini provider; stored progress stays local. Temporary recordings are removed after submission/cancellation.
- Idea review is inexact periodic WorkManager work, at most three unreviewed ideas per execution, subject to network/battery constraints. An already transmitted request cannot be recalled by disabling the toggle.
- Device-only dictation is offered only when Android exposes an on-device recognizer; there is no silent cloud fallback.
- Local data is primary. New tables are not automatically synchronized remotely and no remote AI owns the stored originals.
