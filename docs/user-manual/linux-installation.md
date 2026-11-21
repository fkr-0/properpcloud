# Linux installation

The Linux client is a native JVM desktop application. It does not require Android, Waydroid, an emulator, or a mounted pCloud filesystem.

## Download the latest Linux packages

| Package | Direct download | Notes |
| --- | --- | --- |
| AppImage | [properpcloud {{LATEST_RELEASE_TAG}} x86_64]({{LATEST_APPIMAGE_URL}}) | Portable package with bundled JVM runtime; host `mpv` remains required. |
| Flatpak bundle | [properpcloud {{LATEST_RELEASE_TAG}} x86_64]({{LATEST_FLATPAK_URL}}) | Directly installable bundle; not yet published on Flathub. |
| Checksums | [SHA256SUMS]({{LATEST_CHECKSUMS_URL}}) | Verify downloaded bytes before installation. |
| Release evidence | [release-evidence.json]({{LATEST_EVIDENCE_URL}}) | Records the immutable commit and artifact graph. |

An Arch packaging recipe is [prepared in the repository]({{ARCH_RECIPE_URL}}), but properpcloud is not yet published in the AUR. A Debian/Ubuntu `.deb` is also not currently published.

### Run the AppImage

```bash
chmod +x properpcloud-{{LATEST_RELEASE_VERSION}}-x86_64.AppImage
./properpcloud-{{LATEST_RELEASE_VERSION}}-x86_64.AppImage
```

### Install the Flatpak bundle

```bash
flatpak install --user ./properpcloud-{{LATEST_RELEASE_VERSION}}-x86_64.flatpak
flatpak run dev.fkr.properpcloud.app
```

## Runtime requirements

| Component | Purpose |
| --- | --- |
| Java 17+ runtime | Runs the packaged desktop application when using the Gradle distribution. Native packages bundle an application runtime. |
| mpv | Host codec and streaming playback engine controlled over a private Unix socket. |
| Secret Service provider | Stores the pCloud session token; commonly GNOME Keyring or KWallet through a compatible service. |
| Session D-Bus | Provides MPRIS media controls. The app still runs when MPRIS registration is unavailable. |

### Arch Linux

```bash
sudo pacman -S --needed mpv libsecret
```

Ensure a Secret Service implementation is running in the graphical session. On minimal i3 sessions, `gnome-keyring-daemon --start --components=secrets` is one option.

## Audio containers and codec support

The local audio library can queue audio-bearing WebM (`.webm`) and Matroska (`.mka`, `.mkv`) containers without changing the separate metadata-tag scanner's format policy. The application checks the actual mpv audio track list when loading; a filename extension does not imply that a codec is installed or that the file contains audio.

The client launches mpv with video disabled. It is an audio player, not a video surface: first-class video playback, subtitle selection, and chapter navigation are not supported. Supported audio codec profiles depend on the host mpv build and its FFmpeg configuration. CI qualifies a generated Opus audio profile in WebM and Matroska, but no guarantee is made for all codecs or profiles.

AppImage resolves mpv from the normal host `PATH`. Flatpak invokes the host mpv through its validated host bridge; mpv is not bundled into either package.

## Run from source

The repository pins compilation to JDK 21 while emitting JVM 17 bytecode:

```bash
make desktop-test
make desktop-smoke
make mpv-webm-smoke
make desktop-mpris-smoke
make desktop-run
```

`desktop-smoke` verifies generated audio, recursive queue creation, SQLite persistence, and real mpv JSON IPC with a null audio output. `mpv-webm-smoke` creates small deterministic Opus fixtures for WebM, `.mka`, and `.mkv`, then verifies track discovery, duration, resume seeking, and clean completion with null audio output. It reports an explicit unavailable-codec/tool skip locally; the Linux CI baseline treats missing mpv, ffmpeg, or Opus support as a failure. `desktop-mpris-smoke` builds the packaged runtime, starts an isolated session bus, and queries the exported identity, playback status, and metadata properties externally.

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
host-filesystem access. Install a locally downloaded release bundle with:

```bash
flatpak install --user ./properpcloud-*-x86_64.flatpak
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
- MPRIS URI scheme list is empty; `OpenUri` rejects unsupported URI requests rather than accepting a no-op
- mpv is always launched with `--no-config`, so user mpv settings cannot break or alter application playback
