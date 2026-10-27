# Review notes — branch `fix/a11y-forge-lia` (accessibility + Forge)

Static code review only (no device, no Android SDK in the review environment). Nothing here was compiled or run;
the first real build/test happens when the owner (or Antigravity) builds the APK.

## Changed on `claude/a11y-review-fixes` (small, additive)
1. **A11yWatchdog no longer runs on the main thread.** `inspectAndRecover` is called from `onActivityResumed`;
   it built `AppPreferences` + a full Doctor report there and, with auto-repair enabled, `Thread.sleep(1500)` inside
   `performAutoRebind`. It now runs on a single background executor, throttled to once per 5 s. The boot / package-replaced
   receiver uses `goAsync()`.
2. **Real binding grace period.** The old code passed a hard-coded `uptimeMs = 10000L`, so the "needs restart" notification could
   fire right after boot while the system was still binding the service. It now uses real process uptime (8 s grace, then re-checks
   `instance` before declaring the binding stale).
3. **Notification rate limit survives process restarts** (persisted in `a11y_watchdog` prefs; it was memory-only, so an OEM that
   kills the process re-posted it on every start).
4. **Component spelling normalization** (`pkg/.Cls` vs `pkg/pkg.Cls`) in `removeComponentFromSettings` / `addComponentToSettings`.
   Without it the auto-rebind could fail to remove our own entry and then append a duplicate, so the service was never toggled.
5. **`SLOW_EVENT` breadcrumbs** no longer write the whole log file from inside `onAccessibilityEvent` (main thread); they are
   rate-limited (1 per 5 s) and memory-only until the next regular entry is persisted.
6. **Model ranking tie-break.** `gemini-3.8-flash-lite` used to sort ahead of `gemini-3.8-flash` (plain string compare, longer id
   wins). Same-version order is now: plain stable > preview/experimental > lite. Version still dominates.

## Reviewed and looked fine
- Event-type narrowing in `accessibility_service_config.xml`: only the 5 kept types are referenced anywhere in the project.
- `SecurityManager.verify` only checks the package name (signature check is unused), so it cannot kill a re-signed debug build.
- `ForegroundTypeSelector` + guarded `startForeground` call sites.
- `GeminiModelResolver` fallback flow (404 → next candidate, replacement hint, 429/503 not burning candidates).

## Not changed — open items worth knowing
- **Both `startForeground` attempts failing** in `JarvisConversationService.ensureMicrophoneForegroundService` is only logged. If the
  service was started with `startForegroundService()` and never reaches foreground, Android throws
  `ForegroundServiceDidNotStartInTimeException` and kills the process (and the accessibility service with it). The media-playback
  fallback should normally succeed, so this is rare; a `stopSelf()` guarded by a "foreground established" flag would close it.
- `LiveProbeResult.NULL_ROOT` currently counts as working (reasonable while the display is asleep, but a permanent null root with the
  screen on would be a false "WORKING").
- Doctor/Watchdog verdicts for H1–H7 were derived from code reading, not from device logs. Treat them as hypotheses until the
  Doctor report from a real device is available.
- `fallback`/`403` matching in `executeWithModelFallback` is substring based on the exception message.
