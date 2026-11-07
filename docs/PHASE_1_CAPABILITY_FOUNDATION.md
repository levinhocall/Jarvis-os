# Phase 1 — Capability foundation (personal APK)

## Baseline
Active Android project: `Part 7/`; package: `com.jarvis.assistant`.
Preserve Parts 1–6, existing XML/ViewBinding UI, service ownership, Room schema, and existing tools.

## Scope of this initial change
- Introduce a pure Kotlin declarative capability policy.
- Candidate execution paths: Android API, Accessibility, optional ADB, optional Root.
- No Shizuku integration or dependency.
- Candidate selection does **not** imply permission, consent, capability probing, or command execution.
- Unit tests cover native preference, Accessibility preference, unsupported fallback, and absence of Shizuku.

## Next integration checkpoints
1. Inventory existing command dispatchers, permission handling, and Accessibility ownership.
2. Add device-state probes for Android APIs and user-enabled Accessibility.
3. Introduce authorization and risk checks before routing any actual command.
4. Keep ADB and Root disabled until explicitly configured and verified.
5. Add regression tests and CI verification before wiring to production.

## Verification
Source added on a feature branch. Gradle compilation, Android tests, APK build, and device testing have not been executed by this change.
