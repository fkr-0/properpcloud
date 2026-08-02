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
```

The Compose Desktop packaging configuration produces Linux application images and supports `.deb` and `.rpm` packages. The first project-supported package for Arch remains a planned release artifact; the unpacked application image works independently of the Android toolchain.

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
