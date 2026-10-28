# Phase 2 — Floating Avatar Reliability Baseline

Date: 2026-10-28
Repository: `levinhocall/Jarvis-os`
Module: `Part 7`
Package/application ID: `com.jarvis.assistant`

## Scope and preservation

Phase 2 focuses on the existing floating avatar / task overlay only. No redesign, no changes to the main UI, voice orb, theme, navigation, package name, or existing avatar artwork.

## Existing implementation verified

- `TaskProgressOverlay.kt` creates an Android `TYPE_APPLICATION_OVERLAY` window and checks `Settings.canDrawOverlays(context)` before attaching it.
- The existing overlay supports avatar mode and task-progress mode, drag repositioning, saved coordinates, edge placement, and a transient status pill.
- `JarvisConversationService` owns the overlay and updates it on the main dispatcher; service teardown cancels its update job and destroys the overlay.
- `OverlayAvatarActivity` already controls whether the overlay is enabled and selects existing avatar assets. This phase did not modify that settings UI.
- The overlay's avatar is a WebView backed by packaged assets via `WebViewAssetLoader`; file access is disabled.

## Change committed

Commit: `aa80757dc70631644c499cdb2cb7322252214308`

Added a meaningful Android accessibility `contentDescription` to the existing avatar WebView, describing drag and double-tap behavior. This does not alter its visual appearance or the voice orb.

## Acceptance status

| Check | Status |
|---|---|
| Existing floating overlay implementation located | Complete (code inspection) |
| Overlay permission gate exists | Present in code; device behavior unverified |
| Existing avatar visuals/UI preserved by this change | Complete (change scope) |
| Avatar accessibility description added | Complete (committed) |
| Build/unit tests | Pending; no successful CI build evidence |
| Android runtime: overlay permission, drag, edge positioning, double-tap | Pending device test |
| Background/foreground and process recreation | Pending device test |

## Device acceptance checklist for final APK

1. Enable “Display over other apps” for JARVIS OS and enable the existing avatar overlay setting.
2. Verify avatar appears in the selected existing mode and does not appear when disabled.
3. Drag avatar to each edge; verify it remains within visible screen bounds after orientation/display changes.
4. Double-tap to switch avatar/task status modes; verify task state does not break the overlay.
5. Deny overlay permission and confirm the app fails safely without a crash.
6. Put the app in background/standby and return; verify service teardown/recreation does not leave duplicate windows.
7. Check Android accessibility tooling can identify the floating avatar using its content description.

## Honest completion boundary

The Phase 2 implementation change and repository audit are complete, but runtime acceptance is **not verified** because no device session or successful build result is available here. Phase 3 must not begin until the user approves it.
