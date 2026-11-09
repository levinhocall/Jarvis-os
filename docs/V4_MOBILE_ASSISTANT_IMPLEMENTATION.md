# JARVIS V4 Mobile Assistant — Implementation Workstream

Status: planning and integration audit; no feature implementation or device validation claimed.
Target: `Part 7/`, application ID `com.jarvis.assistant`.
Preserve the existing Android Views/XML architecture, Gemini Live session, Vosk wake word, accessibility automation, Room SQLCipher database and existing migrations. Do not alter Parts 1–6.

## Delivery sequence and acceptance criteria

### 1. Phone calls
- Inspect existing PhoneControlManager, ContactResolver, manifest and conversation tool dispatcher before adding code.
- Announce caller display name only when contact permission is granted, otherwise announce a generic incoming call.
- Voice accept/reject only through supported Android Telecom APIs and roles; gate by platform/role/permission, expose disabled state when unavailable.
- Never silently auto-answer or reject; use an explicit user utterance, with short-lived call-state binding to avoid stale commands.
- Tests: caller unknown, duplicate ring, permissions denied, call ends before command, role unavailable, Bluetooth/audio focus.

### 2. SMS and contacts
- Reuse existing ContactResolver where possible. Request READ_CONTACTS only when needed.
- Resolve ambiguous names by asking which contact; do not speak private numbers aloud unnecessarily.
- Compose SMS with recipient and message preview; require explicit confirmation before sending.
- Prefer ACTION_SENDTO with sms: URI for user-mediated sending. Direct SmsManager only when permissions and distribution policy allow.
- Tests: no contact, multiple matches, Unicode Hindi message, no SIM, permission denied, cancellation.

### 3. Alarm, timer and flashlight
- Use AlarmClock.ACTION_SET_ALARM and ACTION_SET_TIMER with platform fallbacks and user-visible confirmation.
- Use CameraManager.setTorchMode for flashlight with hardware/availability checks; never assume a camera flash exists.
- Time parsing must handle 12/24h, 'kal subah', and local timezone; ask when ambiguous.
- Tests: no alarm app, no flash, camera busy, unsupported Android behavior, repeated commands.

### 4. Persistent memory
- Audit the two existing persistence paths: legacy ChatRepository preferences and Room ConversationRepository.
- Select Room as canonical durable conversation store only after migration and compatibility review; do not discard existing records.
- Reuse MemoryRepository embedding/vector retrieval, deduplication, correction and deletion.
- Add consent-aware memory capture, scope (session/long-term), user-facing forget/inspect actions, retention controls and tests.
- Preserve SQLCipher/Keystore and explicit Room migrations; test upgrades from existing database versions.

### 5. Natural Hindi/Hinglish
- Audit current Gemini Live language/persona prompt and language preference plumbing.
- Add language auto-detection and natural code-switching instructions, preserving exact names, contact spelling and numbers.
- Ensure tool-call intents are structured and validated separately from conversational prose.
- Preserve low-latency barge-in, audio focus and wake word; do not force TTS where native Live audio is active.
- Tests: Hinglish intents, Hindi script, negation, mixed-language contact names, false confirmations, interruption.

## Integration order
1. Inventory Part 7 concrete files, call graph and permission handling.
2. Implement low-risk phone utilities with tests.
3. Implement contacts/SMS compose-confirm-send.
4. Implement call announcement and gated Telecom actions.
5. Reconcile memory stores and add opt-in long-term memory.
6. Improve Hinglish prompting/tool extraction and regression-test audio lifecycle.
7. Review diff, run permissible non-APK checks, document device-only verification.

## Security / release gates
- No automatic outbound SMS, calls or contact disclosure without appropriate user action and consent.
- No accessibility-based workaround for unavailable protected call APIs.
- No new broad permissions unless needed and justified.
- No claims of passing builds/tests unless actually executed.
- Do not run APK-producing Gradle tasks per repository engineering instructions.
