# Phone Whisper privacy policy (self-hosted streaming fork)

This fork retains Phone Whisper's existing on-device and optional legacy API
modes and adds **opt-in self-hosted AIOS transcription**. The default mode on
upgraded installations remains the original legacy/on-device setting; the user
must explicitly enable AIOS streaming or AIOS batch in the app's settings.

## Speech data

- **On-device transcription:** audio stays on the Android device and is
  processed by the downloaded local Sherpa-ONNX model.
- **AIOS streaming:** 16 kHz mono microphone samples are sent continuously
  over an encrypted WSS connection to the endpoint selected by the user.
  Transcript updates return over the same connection. The server processes
  short overlapping windows and emits one final transcript on stop.
- **AIOS local batch / fallback:** the complete recording is transmitted over
  HTTPS to the configured self-hosted server. Automatic fallback can follow
  an interrupted streaming attempt; no cloud STT service is involved.
- **Legacy cloud transcription and cleanup:** the original OpenAI options
  remain selectable in legacy mode only. When selected, the recording or
  transcript is sent to OpenAI according to that provider's terms.

The server that the user configures is operated and secured by that user.
This fork does not ship a shared transcription service, provider credentials
or a tracking/analytics backend. The supplied streaming-server implementation
does not log the raw microphone content, transcript, bearer or credentials
by default; server administrators control their actual deployment, logs and
data retention. The client retains an in-memory PCM buffer temporarily during
dictation to support local batch fallback; it discards the buffer when the
session completes or is cancelled.

## Credentials

The AIOS bearer token is entered by the user, stored in app-private
Android Keystore-encrypted preferences, and sent in the HTTP/WebSocket
Authorization header. It is never embedded in an endpoint URL. App backup
is disabled. The original legacy OpenAI-key setting remains separate and is
only used by the user-selected legacy modes.

## Accessibility and clipboard

The Accessibility service identifies the field focused at dictation start
and attempts **one** final insertion. Provisional results are not written
into target apps. If focus changes or insertion fails, the final transcript
can be copied to the system clipboard; the clipboard is controlled by Android
and other software on the device may access it. Do not use dictation for
passwords or other secrets. The app does not continuously inspect field text
for analytics and does not provide background monitoring.

## Upstream attribution

The original Phone Whisper project and its existing licence remain in the
repository. This document describes the additional privacy implications of
the self-hosted fork, not a claim about the operation of third-party servers.
