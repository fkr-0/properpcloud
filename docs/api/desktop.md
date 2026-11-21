# Desktop platform API

Packages: `dev.fkr.properpcloud.desktop.*`

The desktop classes are public primarily for integration tests and future platform composition. Their behavioral contracts matter more than binary stability before the first desktop release.

## XdgPaths

`XdgPaths.resolve(environment, home)` maps XDG roots and appends `properpcloud`. `create()` creates all directories and attempts owner-only permissions for the runtime directory.

## SqliteStateRepository

Thread-safe methods provide string settings, complete queue replacement/load, and progress upsert/load. The repository enables foreign keys and WAL mode during initialization.

## SecretServiceVault

```kotlin
fun available(): Boolean
fun store(key: String, secret: CharArray)
fun lookup(key: String): CharArray?
fun clear(key: String)
```

The implementation invokes `secret-tool` without a shell. Secret content is written to standard input and the mutable input array is cleared after writing.

## MpvController

`MpvController` owns one host `mpv` process and one private Unix IPC socket. It launches mpv with `--no-config`, no video output, and no video stream (`--vid=no`); incidental video tracks in audio-bearing containers do not create a second playback surface.

Public suspending operations are:

- `ensureStarted`
- `load`
- `togglePause`
- `pause`
- `seekRelative`
- `seekAbsolute`
- `setSpeed`
- `stop`

`load` waits for the matching mpv `start-file` / `file-loaded` event pair before reading the audio track list or applying its optional resume seek. Load generations prevent a stale event from completing a newer load. IPC connect/write/read operations retain bounded timeouts and request-ID correlation. Error output is reduced to fixed failure categories; provider URLs, response bodies and raw mpv diagnostics are not exposed.

`state` exposes `MpvState` as a `StateFlow`. It carries a redacted `MpvFailureKind` distinguishing missing audio, unavailable codec, invalid media and transient network failure. Deterministic media errors do not trigger signed-provider-link refresh. `close` cancels event and state readers, terminates the process, and removes the socket.

## Local media admission

The filesystem audio adapter accepts the ordinary supported audio formats plus these container filename extensions independently of metadata-tag scanning:

| Extension | Queue policy | Reported MIME fallback |
| --- | --- | --- |
| `.webm` | Audio-capable container candidate; actual audio track required at load | `audio/webm` |
| `.mka` | Matroska audio container candidate; actual audio track required at load | `audio/x-matroska` |
| `.mkv` | Matroska container candidate; actual audio track required at load | `audio/x-matroska` |

An extension is not a codec guarantee. Files must still be readable regular files inside the selected canonical root, with no symlink traversal. Unsupported generic files remain visible as generic library files and are not queueable audio. The tag scanner's conservative supported-extension policy is unchanged; container metadata may be absent and filename fallback identity/title is retained.

## MprisService

The service exports the standard root and player interfaces on the session bus. `update(MprisSnapshot)` changes metadata and capabilities and emits a properties-changed signal. Signed URLs are not included in MPRIS metadata. Duration uses stable track metadata when present, falling back to the current live mpv duration.

`SupportedUriSchemes` is empty because the application does not implement safe URI-to-queue identity resolution. `OpenUri` rejects requests instead of reporting a successful no-op. `SupportedMimeTypes` contains only audio MIME types; it does not claim video playback.

## DesktopController

The controller is the desktop application façade. UI callers submit intent-style actions; all network, disk, and playback work is launched off the Compose event thread. `state` is the single observable UI state.

## Codec and feature boundaries

AppImage uses the host `mpv` executable. Flatpak uses the validated host bridge; mpv is not bundled. Audio playback depends on codecs available in the user's host mpv build. CI installs an Opus-capable mpv/ffmpeg baseline and qualifies generated WebM and Matroska fixtures, but this does not guarantee every codec profile on every host.

The Linux client does not provide first-class video playback, subtitle selection, or chapter navigation. WebM/Matroska support here means audio-bearing container playback only.
