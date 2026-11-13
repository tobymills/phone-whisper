# Phone Whisper settings and messaging review — 13 November 2026

Scope: Android settings layout and user-visible status/error copy on `feature/local-streaming-stt`.

## Interface design review

- Group setup permissions, transcription method, server connection, microphone/preview, AI cleanup and last-session diagnostics.
- Show the server streaming URL only in streaming mode, batch endpoint for streaming/batch, and hide legacy-only controls outside legacy mode.
- Use one explicit **Improve text with AI** switch (default enabled for existing users). Tap-to-dictate honours the switch; hold-to-dictate always inserts raw transcription.
- Scale the held microphone from 1.38× to 1.59× (approximately 15% more) while retaining the existing overlay touch target. The handset still needs clearance testing.
- Preserve stored preference keys and service addresses to avoid breaking existing deployments; the cleanup endpoint is now editable.

## Copywriting review

- User-facing text refers to **server**, **live streaming**, **server batch** and **on-device** rather than the homelab host name.
- Streaming fallback messages describe an attempted server batch retry only when a batch endpoint and token are configured.
- If both server methods fail, give an explicit transcription failure. Do not imply a local phone model is used.
- Clean-up failures preserve the original transcript rather than promising AI modification.
- The settings status means *configured*, not verified reachable.

## Compatibility limitations

- Current server endpoint defaults remain for upgraded installations and may be changed in Settings.
- The cleanup response's private status header is optional; explicit raw/fallback statuses are still respected.
- The live streaming protocol remains the existing app-specific WebSocket v1 protocol. The cleanup endpoint still expects the existing authenticated JSON `/v1/text/clean` contract. Configurable URLs do **not** make arbitrary speech servers compatible.
- Batch transcription now has an editable model identifier, defaulting to `large-v3-turbo-q5_0` for compatibility. The server must support the current multipart transcription API.
- No Android-on-device offline fallback is introduced.
- The Android APK and UI changes require physical handset validation; CI is not a substitute.

## Acceptance checks

1. Enable and disable AI cleanup; verify tap-to-record changes whether cleanup is attempted and push-to-talk always remains raw.
2. Verify the 1.59× held button is visible under a thumb and returns to normal on release.
3. Check settings grouping and hiding of irrelevant controls for each transcription mode.
4. Test streaming success, configured server-batch fallback and unconfigured fallback error.
5. Confirm no user-visible app messages refer to the old homelab platform name.
