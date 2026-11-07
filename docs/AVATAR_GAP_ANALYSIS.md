# JARVIS OS — Avatar / Overlay Gap Analysis

Status date: 2026-11-07. Scope: Part 7 (Views/XML). Nothing below was compiled or run on a device by the
author of this document; items marked UNVERIFIED must be checked on a real build.

## 1. What MYRA actually is (public information only)

Source: the vendor's public install guide (github.com/codeninjavik/myra-install-guide). MYRA's internals are not
published (no source in that repo), so nothing here assumes how it is built.

- Voice-first assistant on Google Gemini Live; claims 148+ languages, screen/camera vision, 40+ tools (Windows).
- Android edition is `myra-termux`: runs inside Termux with a browser UI on 127.0.0.1, not a native APK.
- Its own page says Android cannot tap inside other apps without root/ADB, so cross-app UI control is limited there.
- Feature parity differs per platform (e.g. WhatsApp automation is full on Windows, "opens a chat" on Termux).
- YouTube demos could not be reviewed from this environment.

Takeaway: JARVIS already has the same core pipeline (Gemini Live) and goes further on Android (accessibility-based
phone automation, wake word, memory). The gap is the character experience, not the intelligence stack.

## 2. Gap table

| Expectation | Status | Notes |
|---|---|---|
| Large portrait stage, no small circle | DONE (UNVERIFIED on device) | `RealisticAvatarView` PORTRAIT shape, hero image, bottom fade |
| Home always dark neon | DONE (UNVERIFIED) | `Theme.JarvisAssistant.HomeNeon` applied only to Home; dock/status bar still follow app theme |
| Title clipping / dock overlap / spacing | DONE (UNVERIFIED) | Header compacted; Home height = one screen, directive grid below fold |
| Orb + Ghost kept, 3-way selector | DONE | Settings > Assistant Avatar |
| Floating overlay: drag, edge snap, independent toggle | DONE (pre-existing `TaskProgressOverlay`) | Extended, not duplicated |
| Overlay shows AI Girl | DONE (UNVERIFIED) | Settings > Floating overlay > "Use AI Girl as floating avatar" |
| Overlay minimized / expanded (Andee-style edge peek) | DONE (UNVERIFIED) | Slides ~55% off the left/right edge when idle; touch or talking pops it out; own toggle |
| Overlay reacts to listening/speaking | DONE | Conversation state + live audio level |
| Natural blinking, eye movement, facial expressions, confused state | NOT DONE | Needs rigged asset (section 3) |
| Real-time lip-sync | NOT DONE | Current speaking state = voice-reactive glow + bob only. No fake mouth is drawn on purpose |
| Full/upper-body animated character | NOT DONE | Current avatar is one still portrait with procedural motion |
| Hindi / Hinglish / English, context, interruptions | EXISTING | Gemini Live pipeline; overlay tap interrupts playback. Not re-tested here |
| Memory with privacy controls | EXISTING | Not changed |
| Phone actions via existing architecture | EXISTING | Not changed |
| APK build + regression test | NOT DONE here | Sandbox has no Android SDK; build via Android Studio / Antigravity |

## 3. Assets needed for the "alive" character (this is the real blocker)

Code cannot invent animation for a photograph. Pick ONE route:

A. Live2D Cubism model (best for lip-sync + expressions)
   - `.moc3` + textures + `.model3.json` + motions; standard params: `ParamMouthOpenY`, `ParamMouthForm`,
     `ParamEyeLOpen/ROpen`, `ParamEyeBallX/Y`, `ParamAngleX/Y/Z`, `ParamBreath`, plus expression files
     (happy, confused, thinking). Commissioned from a Live2D artist; Cubism SDK for Android must be added
     (check its license terms for your use).
B. Rive file (`.riv`) with a state machine
   - Inputs: `state` (idle/listening/thinking/speaking/happy/confused/error), `mouthOpen` (0..1 number),
     `blink` trigger. Lightweight, good fit for Android.
C. Per-state video loops (fastest, most realistic look, approximate lip-sync)
   - Transparent or dark-background MP4/WebM, 3-6 s seamless loops: idle, listening, thinking, speaking
     (several variants), happy, confused, acknowledge. Same camera and pose in every clip so crossfades don't pop.
     Mouth cannot follow exact phonemes; it loops while audio plays.

When an asset exists, add a renderer behind the same API `RealisticAvatarView` exposes
(`setStateKey`, `setAmplitude`, `playHappy`, `playAcknowledge`, `shape`). Hosts (Home, overlay) need no change.

## 4. Known limits of what shipped

- Hero image is a 645x590 crop of the mockup, upscaled 1.5x. Replace `res/drawable-nodpi/avatar_girl_hero.webp`
  with a proper high-resolution render of your character.
- Peek positions are computed from window gravity; test on left and right edges and after rotation.
- Status/navigation bar icon colors follow the app theme, so on the light theme they may be low-contrast over the
  dark Home.
- `avatar_girl_{idle,listening,thinking,speaking,happy,ack}.webp` (small mockup tiles) are no longer used by the
  view were removed.

## 5. Suggested next phases

1. Build and device-test everything above (Home on 360dp and 412dp widths; overlay on both edges; permission flow).
2. Commission or generate the asset (route A, B or C), then add the renderer.
3. Optional: Andee-style system-wide "walk along edges" behaviour needs sprite art for that pose; mechanics already exist.
4. Accessibility upgrades from Andee (element-ID outline, blind-tree grid fallback, effect checks) as a separate branch,
   keeping Andee's license notices on any ported code.
