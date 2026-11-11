# Stable APK signing for in-place Android updates

The **debug artifact is not suitable for updating an existing installation**:
GitHub-hosted runners generate their own debug signing identity.

To enable `phone-whisper-signed-release`, configure these **GitHub Actions repository secrets**:

- `PHONE_WHISPER_KEYSTORE_BASE64`: base64 of a securely stored Android keystore.
- `PHONE_WHISPER_STORE_PASSWORD`: its keystore password.
- `PHONE_WHISPER_KEY_ALIAS`: the signing key alias.
- `PHONE_WHISPER_KEY_PASSWORD`: the key password.

Create a long-lived key **once**, off-repository, using the JDK `keytool`,
or reuse your **existing installed APK's signing key** if you still possess it.
Keep an offline backup of the keystore and credentials. Never commit any of them.

Example on a trusted machine with JDK installed:

```sh
keytool -genkeypair -v -keystore phone-whisper-release.jks \
  -alias phonewhisper -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 phone-whisper-release.jks  # Linux; store output in GitHub secret
```

On Windows PowerShell, encode using
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("phone-whisper-release.jks"))`.

Actions always tests, lints and builds a **debug** APK. When all four secrets
exist, it additionally builds a **signed release** APK with the persistent
certificate and a CI-incremented version code. Only distribute the signed
release artifact for future in-place upgrades. Release builds use a distinct
optimisation/build type from debug, so test the signed APK on-device.

**One-time transition:** Android will not normally update an app signed by a
different key. If your installed APK was signed with an ephemeral debug key
and that private key is unavailable, back up/re-enter settings and perform one
final uninstall/reinstall to adopt the stable release certificate.

The application's package name stays `com.kafkasl.phonewhisper`. Enabling
these secrets cannot alter already-installed APKs. Do not expose passwords,
keystore bytes or signing credentials in GitHub issues, chat or commits.
