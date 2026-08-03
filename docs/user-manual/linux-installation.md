# Linux installation

The Linux client is a native JVM desktop application. It does not require Android, Waydroid, an emulator, or a mounted pCloud filesystem.

## Runtime requirements

| Component | Purpose |
| --- | --- |
| Java 17+ runtime | Runs the packaged desktop application when using the Gradle distribution. Native packages bundle an application runtime. |
| mpv | Codec and streaming playback engine controlled over a private Unix socket. |
| Secret Service provider | Stores the pCloud session token; commonly GNOME Keyring or KWallet through a compatible service. |
| Session D-Bus | Provides MPRIS media controls. The app still runs when MPRIS registration is unavailable. |

### Arch Linux

```bash
sudo pacman -S --needed mpv libsecret
```

Ensure a Secret Service implementation is running in the graphical session. On minimal i3 sessions, `gnome-keyring-daemon --start --components=secrets` is one option.

## Run from source

The repository pins compilation to JDK 21 while emitting JVM 17 bytecode:

```bash
make desktop-test
make desktop-smoke
make desktop-mpris-smoke
make desktop-run
```

`desktop-smoke` verifies generated audio, recursive queue creation, SQLite persistence, and real mpv JSON IPC with a null audio output. `desktop-mpris-smoke` builds the packaged runtime, starts an isolated session bus, and queries the exported identity, playback status, and metadata properties externally.

## Build a distributable

```bash
make desktop-package
make desktop-appimage
make desktop-flatpak
# or both release formats:
make linux-packages
```

`desktop-package` produces the unpacked Compose application image. `desktop-appimage`
wraps that image with checksum-pinned AppImage tooling and a pinned type-2 runtime.
`desktop-flatpak` produces a directly installable single-file Flatpak bundle using the
Freedesktop 25.08 runtime. Since the application is already compiled into its jlink
image, packaging does not download or invoke the Freedesktop compiler SDK.

Both packages bundle properpcloud's jlink application runtime but intentionally retain
the existing `mpv` system dependency. The AppImage resolves `mpv` from the normal host
`PATH`. The Flatpak uses its narrowly declared `org.freedesktop.Flatpak` D-Bus permission
to invoke the host `mpv` through `flatpak-spawn` and shares only
`$XDG_RUNTIME_DIR/properpcloud` for the private IPC socket; it does not grant broad
host-filesystem access. Install a release bundle with:

```bash
flatpak install ./properpcloud-*-x86_64.flatpak
```

The GitHub Linux workflow builds and smoke-tests both package formats, and tagged release
workflows attach them beside the Android APK with one checksum manifest and provenance
record. The Flatpak smoke verifies the installed application runtime, MPRIS export, and
host-mpv IPC socket through the shared XDG runtime subdirectory.

## Data locations

properpcloud follows the XDG Base Directory specification:

```text
$XDG_CONFIG_HOME/properpcloud   configuration
$XDG_DATA_HOME/properpcloud     SQLite state
$XDG_CACHE_HOME/properpcloud    generated demo media and disposable cache
$XDG_RUNTIME_DIR/properpcloud   private mpv IPC socket
```

When an XDG variable is absent, the normal user-home fallback is used. Runtime files fall back to a user-specific temporary directory.

## Desktop integration

- MPRIS bus name: `org.mpris.MediaPlayer2.properpcloud`
- Desktop entry: `properpcloud`
- Media controls: play, pause, next, previous, seek, and position
- mpv is always launched with `--no-config`, so user mpv settings cannot break or alter application playback.
