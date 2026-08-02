# Android installation

## Requirements

- Android 8.0 or newer (`minSdk 26`).
- Network access for pCloud browsing and streaming.
- A pCloud account in either the European or United States region when using the provider source.

## Install a release APK

1. Download the APK from the matching GitHub release.
2. Verify the published SHA-256 checksum when one is provided.
3. Allow installation from the browser or file manager used for the download.
4. Install the APK and revoke the temporary installer permission afterward.

The project does not currently publish through Google Play. Release artifacts are produced from the repository workflow and should be obtained from the project release page rather than third-party mirrors.

## Developer installation

From a clean checkout with Docker available:

```bash
make doctor
make test
make build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build accepts `PCLOUD_CLIENT_ID` for browser OAuth-capable builds. The direct account flow is available as an interim provider integration and does not require embedding a client secret.

## Android background playback

Playback runs through an Android Media3 service. The system notification and lock-screen controls remain available while the activity is not visible. Battery restrictions imposed by the device vendor may still stop long-running playback; exempt properpcloud from aggressive battery optimization only when necessary.
