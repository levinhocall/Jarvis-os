# Phase 4 — Screen Sharing Reliability

Date: 2026-10-28
Repository: `levinhocall/Jarvis-os`
Module: `Part 7`
Package/application ID: `com.jarvis.assistant`

## Scope

Harden the existing opt-in Android MediaProjection screen-sharing path. Preserve the current UI, voice orb, navigation, app identity, and screen-sharing consent prompt. No new screen-sharing UI was introduced.

## Existing flow confirmed by code inspection

- The user starts sharing from the existing Home screen control.
- Android's MediaProjection consent screen is launched; sharing does not start silently.
- The app requires the live JARVIS session and elevates the existing foreground service to include the media-projection type before capture.
- Screen frames are downscaled and JPEG-encoded, then sent to the live WebSocket only while capture is active and the socket is ready.
- Stopping sharing releases the virtual display, ImageReader, projection callback, projection token, background thread, and latest bitmap.

## Change committed

File: `Part 7/app/src/main/java/com/jarvis/assistant/vision/ScreenCaptureManager.kt`

Commit: `caa81195d0ad7be80249517d64e31017c128db37`

- Ignores/drains queued ImageReader callbacks after sharing has stopped, avoiding stale frames being published or `hasReceivedFrame` becoming true again during teardown.
- Checks that sharing is still active before delivering a JPEG to the frame consumer.
- Catches frame-consumer exceptions locally so a consumer failure does not escape the image callback.
- Resets frame timestamps and first-frame state when a new capture session begins.

## Acceptance status

| Check | Status |
|---|---|
| Existing user-consented MediaProjection flow reviewed | Complete (code inspection) |
| Stale queued-frame teardown guard | Committed |
| Frame callback isolation | Committed |
| Per-session frame state reset | Committed |
| UI/orb/navigation changed | No |
| Gradle compile and unit tests | Pending |
| Android 16 device test (start, receive frame, stop, start again) | Pending |
| System revoke/stop and rapid start-stop test | Pending |

## Device verification checklist

1. Start the live session and explicitly approve Android's screen-capture prompt.
2. Confirm the UI reports sharing only after a frame arrives.
3. Stop sharing and confirm no further frames are delivered.
4. Start sharing again and confirm a fresh frame arrives.
5. Revoke sharing through Android/system UI and confirm the service returns to its non-projection foreground state without a crash.
6. Confirm screen sharing does not alter the home UI or voice orb.

## Completion boundary

Phase 4 code change is committed, but build and physical-device verification are still pending. Do not start Phase 5 until the user explicitly approves it.
