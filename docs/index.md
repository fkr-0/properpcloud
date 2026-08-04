# properpcloud documentation

properpcloud is a folder-first audio player for pCloud libraries. It treats cloud folders as the primary catalog, builds deterministic playback queues without requiring complete tags, and keeps signed provider URLs out of persistent state.

<section class="release-overview" aria-labelledby="latest-release-heading">
  <div>
    <p class="release-overview__eyebrow">Latest stable release · published {{LATEST_RELEASE_DATE}}</p>
    <h2 id="latest-release-heading">properpcloud {{LATEST_RELEASE_TAG}}</h2>
    <p class="release-overview__summary">Verified Android and Linux builds are published together with SHA-256 checksums and commit-bound release evidence.</p>
  </div>
  <div class="release-overview__actions">
    <a class="release-action release-action--primary" href="{{LATEST_RELEASE_URL}}">Open release</a>
    <a class="release-action" href="{{LATEST_CHECKSUMS_URL}}">Checksums</a>
    <a class="release-action" href="changelog/">Changelog</a>
  </div>
</section>

## Download and install

<div class="download-grid">
  <a class="download-card" href="{{LATEST_APK_URL}}">
    <span class="download-card__platform">Android 8+</span>
    <strong>Installable APK</strong>
    <span class="download-card__description">Debug-signed evaluation build with Media3 background playback and the native pCloud client.</span>
    <span class="download-card__action">Download APK →</span>
  </a>
  <a class="download-card" href="{{LATEST_APPIMAGE_URL}}">
    <span class="download-card__platform">Linux x86_64</span>
    <strong>AppImage</strong>
    <span class="download-card__description">Portable desktop package with its JVM runtime bundled; install <code>mpv</code> on the host.</span>
    <span class="download-card__action">Download AppImage →</span>
  </a>
  <a class="download-card" href="{{LATEST_FLATPAK_URL}}">
    <span class="download-card__platform">Linux x86_64</span>
    <strong>Flatpak bundle</strong>
    <span class="download-card__description">Single-file Freedesktop 25.08 bundle, smoke-tested with MPRIS and narrowly bridged host mpv IPC.</span>
    <span class="download-card__action">Download Flatpak →</span>
  </a>
</div>

The release also includes [machine-readable evidence]({{LATEST_EVIDENCE_URL}}), a [source archive]({{LATEST_SOURCE_ARCHIVE_URL}}), and one checksum manifest covering every distributable.

### Package-channel availability

| Channel | Availability |
| --- | --- |
| Android APK | Published on the [latest GitHub release]({{LATEST_RELEASE_URL}}); Google Play is not used yet. |
| AppImage | Published and smoke-tested on the release page. |
| Flatpak | A directly installable `.flatpak` bundle is published; it is not yet listed on Flathub. |
| Arch Linux / AUR | An [immutable-source PKGBUILD renderer]({{ARCH_RECIPE_URL}}) is prepared, but no AUR package is published yet. |
| Debian / Ubuntu `.deb` | Not currently published. Use AppImage or the Flatpak bundle. |
| Source | Clone the repository or download the tagged source archive. |

## Choose a starting point

| Goal | Start here |
| --- | --- |
| Install and use the application | [User manual](user-manual/README.md) |
| Run the Linux desktop client | [Linux installation](user-manual/linux-installation.md) |
| Connect a pCloud account safely | [Accounts and security](user-manual/accounts-and-security.md) |
| Build or contribute | [Developer guide](development/README.md) |
| Integrate a new source or persistence adapter | [API reference](api/README.md) |
| Understand trust boundaries | [Architecture](architecture.md) and [privacy](privacy.md) |
| Review changes and known limitations | [Changelog](changelog/) |

## Current clients

- **Android:** Jetpack Compose UI with Media3 playback and encrypted local session storage.
- **Linux desktop:** Compose Desktop UI, mpv JSON IPC playback, SQLite state, Secret Service credentials, XDG paths, and MPRIS media controls.
- **Deterministic demo source:** local generated WAV files for verification without network access or credentials.

## Core promises

1. A folder can be browsed and queued even when embedded metadata is absent or inconsistent.
2. Provider identity uses stable source and node identifiers, never temporary download URLs.
3. Queue and progress survive application restarts.
4. Credentials are delegated to platform credential stores.
5. Metadata repair is explicit, previewable, and separate from ordinary playback.

## Recent releases

{{RECENT_RELEASES_TABLE}}

For complete user-visible changes, security notes, tests, and known limitations, read the [full changelog](changelog/).

## Documentation as code

All published pages originate as Markdown under `docs/`, with the root changelog and canonical version data incorporated during the Astro Starlight build. GitHub Pages serves the generated static HTML.
