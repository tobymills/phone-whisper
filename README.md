<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Hold the floating button for quick raw dictation, or tap to start and tap again for AI-cleaned dictation; your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI Whisper
- **Optional tap-to-clean dictation** through AIOS/Ollama; OpenAI is only retained for legacy cloud transcription

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl)


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/kafkasl/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Hold and release to record and insert raw transcription, or tap once to start AI-cleaned dictation
3. For AI-cleaned dictation, tap again to stop and run local AIOS/Ollama cleanup
4. Audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: paste your OpenAI API key

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap or hold the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device
- **Cloud mode**: audio is sent directly from your device to OpenAI's transcription API
- **Tap-to-clean dictation**: the final transcript is sent to the authenticated AIOS text-cleanup endpoint backed by local Ollama; hold-to-dictate never calls cleanup

I don't run a backend for this app. In cloud mode, requests go straight from your phone to OpenAI using your own API key.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size | Notes |
|---|---:|---|
| Parakeet 110M | 100 MB | Best default |
| Whisper Base | 199 MB | Solid baseline |
| Parakeet 0.6B | 465 MB | Best quality |
| Moonshine Tiny | 103 MB | Fastest |

The app downloads and extracts models directly from the sherpa-onnx release archives.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI API key

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.


## Local AIOS streaming fork (feature branch; experimental)

This fork adds an optional local streaming dictation transport while retaining
the original floating Accessibility microphone, on-device Sherpa-ONNX models,
legacy batch options and clipboard insertion fallback. The streaming code
does not use the legacy OpenAI *transcription* endpoint. A deliberate tap-to-start/tap-to-stop session sends its completed transcript through the authenticated AIOS gateway to local Ollama cleanup; hold-and-release never sends text for cleanup.

Read [STREAMING-STT.md](STREAMING-STT.md) for the exact WebSocket v1 protocol,
security and failure behaviour, Android acceptance criteria and independent
Claude review guidance.

From the application settings, choose **AIOS streaming**, enter the WSS URL
(`wss://stt.millsho.me/v1/audio/stream` by default), local HTTPS batch URL
(`https://stt.millsho.me/v1/audio/transcriptions`), and the existing gateway
bearer token. The token is provided by the user on the phone and not bundled
in this repository. The user has verified a successful AIOS streaming dictation on 10 November 2026;
this does not yet constitute acceptance of long sessions, fallback reliability,
or the new overlay UI on a physical device.

Existing installations remain in the legacy on-device/API transport until the user explicitly selects AIOS streaming or local batch. The AIOS token is encrypted using Android Keystore and is never embedded in the APK. The app streams PCM16LE mono 16 kHz continuously while the microphone is
recording. The overlay can show unstable and stable text; these updates
never modify another application's field. To cancel a tap-to-record session, hold the microphone for 350 ms; it discards the recording without insertion. Holding from idle records raw dictation and releases to finish. After the explicit stop and final
server result, the app inserts once into the field that was focused at
recording start, or copies to the clipboard if focus changed. A broken
stream results in an explicit local batch fallback from the preserved recording; a no-speech final does not retry batch. If the beginning of the connection takes too long, the app retains audio but switches to batch rather than risking out-of-order sample delivery.
For manual batch-only use, choose **AIOS local batch**.

Offline Android checks (requires JDK 17 and Android SDK 34):

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The earlier release has been tested on AIOS with successful authenticated internal
and public WebSocket handshakes, and a real phone has produced a transcription
using AIOS streaming. The updated overlay and diagnostics features still require
a new APK and device acceptance.

### Overlay and troubleshooting

The **Microphone button** setting offers **Only when typing** (default)
or **Always**. In focused-only mode, the overlay appears only while a
non-password editable input has focus, but stays visible during dictation and
finalisation. **Recess button when idle** partially hides the idle microphone
at the nearest edge after 2.5 seconds; dragging repositions it. Live transcript
and brief status messages appear in a non-interactive panel near the top of
the display, not next to the stop control.

**Last dictation** in Settings displays the last local session stage and
transcription source (streaming, batch fallback, local batch or legacy) with a
short correlation ID; it is not a transcript history. For further investigation
use Android Logcat tags `PhoneWhisperSession` and `PhoneWhisperStream`
and the matching `session=<id>` in AIOS `stt-streaming` logs. Never copy
credentials, transcript text or audio into diagnostic reports. The gateway
and inference process are separate from the Android UI; a successful WebSocket
handshake by itself does not prove audio decoding.

Legacy still requires an on-device model or OpenAI API key; reinstalling the
app removes downloaded models and local settings. The AIOS local batch mode
is independent of those requirements.
