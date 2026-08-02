<p align="center">
  <img src="docs/assets/properpcloud-badger.svg" alt="properpcloud badger cloud" width="720">
</p>

# properpcloud

Folder-first Android audio playback for pCloud and other file-oriented sources.

[![Version](https://img.shields.io/badge/version-0.1.0-59636e)](CHANGELOG.md)
[![License: MIT](https://img.shields.io/badge/license-MIT-2f855a)](LICENSE)

Audio libraries are often already organized correctly in folders. properpcloud
keeps that structure visible instead of forcing every file through unreliable
artist/album/song tags.

> Independent software. Not affiliated with or endorsed by pCloud AG.

## What `0.1.0` provides

- adaptive Material 3 Android UI with compact and expanded layouts;
- stable-ID folder navigation and breadcrumbs;
- natural filename, disc/track, title, and modification-time sorting;
- track, folder, and recursive-subtree queue actions;
- atomic replace/play-next/append semantics with cancellation and partial-result reporting;
- queue reorder, removal, selection, persistence, and containing-folder navigation;
- Media3 background playback, system controls, seeking, and smart resume;
- just-in-time pCloud stream-link resolution with one bounded expiry retry;
- encrypted pCloud OAuth token storage using Android Keystore AES-GCM;
- raw provider/identity inspection without exposing secrets;
- a deterministic built-in demo library with generated WAV audio.

The demo source is not a screenshot mode. It exercises the real browser, queue,
persistence, Media3 service, decoder, and responsive UI without credentials,
network access, or private fixtures.

## First run

1. Install the APK and open **Demo library**.
2. Browse folders and play tracks immediately.
3. To use pCloud, create a pCloud application, copy its client ID, and enter it
   under **Settings → pCloud OAuth**.
4. Authorize on pCloud's trusted surface. properpcloud never asks for the account
   password.

Detailed setup and validation instructions are in `docs/pcloud-setup.md`.

## Security and privacy model

```yaml
credentials:
  password_collection: never
  OAuth_token: Android_Keystore_AES_GCM
  backup: excluded
network:
  cleartext: disabled
  signed_stream_URLs:
    resolved: immediately_before_playback
    persisted: never
backend:
  mandatory_properpcloud_service: false
telemetry:
  analytics: none
  advertising: none
```

Only pCloud's documented regional API hosts are accepted:

- `api.pcloud.com`
- `eapi.pcloud.com`

See `SECURITY.md`, `docs/privacy.md`, and `THIRD_PARTY_NOTICES.md`.

## Validation status

Public CI validates:

- YAML product/architecture/test/release traceability;
- SemVer, changelog, license, and Android package agreement;
- queue, traversal, cancellation, progress, identity, and provider contracts;
- deterministic demo-source and generated-WAV behavior;
- DataStore queue/progress round trips;
- Robolectric Compose compact navigation and rendering;
- Android lint with zero errors and zero warnings;
- debug APK assembly in the pinned Docker toolchain.

Live pCloud account validation is deliberately separate because public CI has no
provider credentials. The maintainer checklist covers US/EU accounts, large
folders, codecs, revocation, process death, and expired links. Until that
checklist has evidence, release notes state that the implementation is validated
with deterministic fixtures while live-provider validation remains outstanding.

The published APK is debug-signed and suitable for evaluation. Production signing
keys remain outside the repository and Docker layers.

## Build

Requirements:

- Docker with BuildKit;
- host `adb` only for installation/device inspection.

The container supplies:

```yaml
Java: Eclipse Temurin 21
Gradle: 9.6.1
Android_compile_SDK: 37
Android_target_SDK: 36
Android_build_tools: 37.0.0
```

API 37 is used to compile current stable AndroidX libraries; target API remains
36 until the protected Android 17 runtime compatibility matrix passes.

```sh
make image
make doctor
make release-check
make test
make lint
make build
# or the complete merge/release gate:
make ci
```

Install through host ADB:

```sh
make install
```

Prepare versioned checksums, release notes, and evidence after a green build:

```sh
make release-artifacts
```

## Repository structure

```text
properpcloud/
├── app/                    Compose UI, encrypted credentials, persistence, Media3
├── core-model/             source-neutral identity, queue, progress, sorting
├── source-pcloud/          official pCloud java-core adapter
├── source-webdav/          optional interoperability boundary
├── docs/                   design, setup, privacy, roadmap, releases
├── spec/                   normative YAML contracts and release DoDs
├── scripts/                verified downloads, spec/release checks, artifacts
├── .github/workflows/      CI and tag-driven release publication
├── Dockerfile
├── Makefile
├── VERSION
└── CHANGELOG.md
```

## Release line

| Version | Contract |
|---|---|
| `0.0.1` | Verified architecture/build bootstrap |
| `0.1.x` | Validated Android folder-first client |
| `0.2.x` | Native Linux desktop client with Android feature parity |
| `1.0.0` | Stable cross-platform contracts and migrations |

`VERSION` is canonical. See `docs/versioning.md` and `CHANGELOG.md`.

## Linux `0.2.0`

The desktop client is specified as native Linux software, not an Android wrapper:

- shared Kotlin/JVM domain and pCloud java-core adapter;
- Compose Multiplatform Desktop;
- mpv JSON IPC playback;
- SQLite persistence;
- Secret Service or KWallet credentials;
- MPRIS, media keys, notifications, and XDG paths;
- GNOME, KDE Plasma, and i3 validation.

See `docs/linux-client.md`, `spec/linux-client.yml`, and `docs/roadmap.md`.

## Design index

- `spec/manifest.yml` — normative index and invariants
- `spec/product.yml` — release feature sets and quality attributes
- `spec/use-cases.yml` — complete and failure-path user journeys
- `spec/architecture.yml` — layers, dependencies, data flows, security
- `spec/contracts.yml` — ports, records, persistence, events, errors
- `spec/ux.yml` — Android and Linux interaction/accessibility behavior
- `spec/testing.yml` — fixtures, fault injection, and release gates
- `spec/definition-of-done.yml` — feature/release completion rules
- `spec/build.yml` — Docker, Gradle, CI, cache, and supply chain
- `spec/linux-client.yml` — native desktop parity architecture
- `docs/roadmap.md` — release-oriented implementation roadmap

## Licensing

Original code is licensed under MIT. Dependencies retain their own licenses,
including Apache-2.0 components listed in `THIRD_PARTY_NOTICES.md` and bundled
with the APK. No copyleft player code is copied into this repository.
