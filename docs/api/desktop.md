# Desktop platform API

Packages: `dev.properpcloud.desktop.*`

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

`MpvController` owns one mpv process and one private socket. Public suspending operations are:

- `ensureStarted`
- `load`
- `togglePause`
- `pause`
- `seekRelative`
- `seekAbsolute`
- `setSpeed`
- `stop`

`state` exposes `MpvState` as a `StateFlow`. `close` cancels polling, terminates the process, and removes the socket.

## MprisService

The service exports the standard root and player interfaces on the session bus. `update(MprisSnapshot)` changes metadata and capabilities and emits a properties-changed signal. Signed URLs are not included in MPRIS metadata.

## DesktopController

The controller is the desktop application façade. UI callers submit intent-style actions; all network, disk, and playback work is launched off the Compose event thread. `state` is the single observable UI state.
