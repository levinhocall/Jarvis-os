# JARVIS OS — Sensitive Permission Governance (Phase 1.2 F9)

Scope: Part 7, package `com.jarvis.assistant`. No Shizuku. No permission was added or removed in Phase 1.2;
each one below is used by a shipped feature, so removal would break working behaviour.
`SensitivePermissionGovernanceTest` fails if this set changes without a conscious update of this file.

| Permission | Feature that needs it (call sites) | Grant model | Behaviour when denied |
|---|---|---|---|
| `QUERY_ALL_PACKAGES` | Launching/resolving installed apps by name (`util/AppLauncher`, `automation/integration/UniversalActionRegistry`, `automation/whatsapp/WhatsAppAssistant`, `automation/phone/PhoneControlManager`, `background/DeviceActionDispatcher`) | Install-time (Play policy review needed for store release) | App lookup returns "not found"; other features unaffected |
| `WRITE_SETTINGS` | System brightness/volume style controls (`automation/phone/PhoneControlManager`, `util/PermissionHelper`) | Special access; user enables via `ACTION_MANAGE_WRITE_SETTINGS` | `PhoneControlManager` checks `Settings.System.canWrite` before each write (2 sites) |
| `CALL_PHONE` | Voice-initiated calls (`util/ContactCaller`, `PhoneControlManager`, `DeviceActionDispatcher`) | Runtime | `PhoneControlManager` checks the grant and falls back to `ACTION_DIAL`. `ContactCaller` and `DeviceActionDispatcher` start `ACTION_CALL` directly; their denial path was not confirmed by this review (see rule 4) |
| `SYSTEM_ALERT_WINDOW` | Wake glow, task progress and avatar overlays (`ui/directives/WakeGlow`, `ui/agent/TaskProgressOverlay`, `ui/settings/OverlayAvatarActivity`, `HomeFragment`) | Special access; `canDrawOverlays` | Overlay features stay off; core app works |
| `com.termux.permission.RUN_COMMAND` | Optional Termux bridge for the developer workspace (`automation/dev/TermuxBridge`, `DevToolRegistry`, `ui/dev/CodingDevFragment`) | Runtime + Termux `allow-external-apps` | Bridge reports unavailable; host/sandbox paths still gated by `ShellPolicy` |

Rules going forward
1. A new sensitive permission needs: a feature that cannot work without it, a denial path, and an entry here.
2. Every use is user-initiated or policy-gated (`ShellPolicy`, approval flow); none may run silently in background.
3. Permission use never logs contact names, numbers, message text or package inventories.
4. Open item: verify/add a CALL_PHONE grant check in `ContactCaller` and `DeviceActionDispatcher` before `ACTION_CALL` (not changed in 1.2: needs device testing of the denial flow).
5. Deferred: per-feature runtime consent UI audit (Phase 1.5) and Play-policy declaration for `QUERY_ALL_PACKAGES`.

Verification status: static review of call sites only. Denial behaviour has not been exercised on a device.
