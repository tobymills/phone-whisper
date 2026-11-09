# Phone Whisper fork: local streaming dictation contract

Status: **repository development**, not Android-device or AIOS live-verified. Starting main: `e6518cda437ed2c266f36cf476db857fa2690d87`. Feature branch: `feature/local-streaming-stt`.

## Fork and compatibility

This fork retains the existing Phone Whisper application identity and features: draggable floating microphone Accessibility overlay, focused-field discovery, Android text insertion with clipboard fallback, microphone permission, local Sherpa-ONNX model downloads, current API-key/settings and existing batch mode. Preserve upstream attribution in README/LICENSE. Android minSdk 30, targetSdk 34, Kotlin/Gradle project; do not add permissions unnecessarily. Gboard remains the user's keyboard. Avoid replacing the app architecture without a documented defect.

## Intent and architecture

Default new transport: `wss://stt.millsho.me/v1/audio/stream` on AIOS through existing NPM, gateway and a separate local ROCm Whisper streaming instance. It is explicitly configurable in the app; bearer token is user-entered (never embedded in code) and sent in the WebSocket **handshake Authorization header**, not URL/query strings. No cloud transcription provider is used for streaming or its fallback. The legacy on-device and API batch code remains available, but AIOS batch is the preferred **local** fallback for network streaming.

The wire version 1 contract is defined in `tobymills/Homelab-Scripts/apps/speech/STREAMING-STT.md`. Client sends JSON start, awaits ready, then chronological binary PCM16LE mono 16 kHz frames from AudioRecord, then JSON stop. Server sends revisioned update messages (stable append-only + replaceable unstable), and exactly one final. Updates are informational in the overlay **only**; never insert tentative or stable partial text into the app under edit. On stop wait for final; insert the completed text **once** via the existing Accessibility path. Do not run cloud post-processing; when using streaming, bypass any existing OpenAI cleanup toggle.

## Session and error model

Client states: idle → connecting → recording → finalising → inserting → idle, with explicit cancelled/failed exits. On initial connect failure, tell the user; optionally switch to local batch using a complete buffered capture, only if the selected fallback allows it and size limits are met. If a socket drops mid-dictation, **do not reconnect as if no samples were lost**. Either send the complete buffered recording to the local batch endpoint or fail explicitly, leaving the clipboard/target untouched. Never silently paste partial text. Track the selected focused field at session start; avoid inserting into a different window without the user explicitly refocusing. Cancel on Accessibility service destruction, microphone failure or permission loss. Handle Android activity/service lifecycle, background execution restrictions, audio interrupts, handshake/idle/stop timeout, invalid server messages, WebSocket closure, repeated tap and session generation races.

The current Android batch implementation uses Sherpa-ONNX locally or OpenAI endpoints. Preserve that code as legacy compatibility, but **new local-only streaming + batch fallback must never invoke it**. Store server URL/token only in private app preferences (no logs/crash messages). Require TLS for off-host endpoints; do not accept insecure `ws://` by default. Limit locally retained audio and avoid logging audio, transcript, access tokens or target field text.

## Build and tests

Gradle 8/Android Gradle Plugin and Kotlin versions are defined by `settings.gradle.kts` and `build.gradle.kts`. Use `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` on a machine with Android SDK/JDK. Tests: protocol parser and monotonic revision/stable prefix; single final insertion, duplicate final refusal, empty final, cancellation and disconnect, fallback eligibility, wrong handshake, audio frame/order, and state restoration. Real device tests: permissions, overlay across target apps, focus lost/changed, clipboard fallback, long dictation, network interruption and screen lock.

## Acceptance criteria

Start mic → continuous PCM transport to AIOS while speaking; useful stable/unstable feedback; stop → server processes the tail and replies final → app pastes **once** into original focused field. No cloud requests in streaming or its local batch fallback. Keep on-device/batch modes selectable, preserve the normal keyboard and overlay. Measure stop-to-insert latency on the actual phone/server; distinguish measured and assumed performance. This branch must not be merged or deployed without independent review and device/AIOS testing.

## Claude independent validation (separate reviewer session)

Review changed code against main and this contract, not the docs alone. Compare overlay/Accessibility, permission, model-download and legacy batch paths with the original fork; inspect recording buffer threading, WebSocket handshake/auth/TLS, lifecycle, partial/stable/final state, duplicate or dropped boundaries, focus and Accessibility injection safety, cancellation, mid-stream disconnect, reconnect/fallback and timeouts. Specifically test that a partial transcript cannot touch target text and that only one final insert is possible. Confirm audio capture is continuous and sample format correct. Inspect preference/token storage, logs and manifests for secret leakage; verify no cloud request exists in streaming/local fallback. Run Gradle build, unit tests and lint; check long recordings and memory limits. Report **Critical/High/Medium/Low** with file and reproduction; list tested and not-tested behaviours separately. Never describe unit tests as live Android/AIOS verification. Do not deploy, merge or alter production.
