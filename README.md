# properpcloud

Folder-first Android audio playback for pCloud and other file-oriented sources.

The project exists because an audio collection is often already organised correctly in the filesystem. A player should not destroy that structure by forcing every file through unreliable tag-derived artist/album/song views.

> Working title. This project is independent and is not affiliated with or endorsed by pCloud AG.

## Decision

The official pCloud Android application does not appear to have a publicly available source repository. The official pCloud Java/Android SDK is public, Apache-2.0 licensed, and actively maintained. pCloud also documents a full HTTP/JSON API, OAuth 2.0, expiring direct file links, folder listing, and incremental filesystem changes.

Therefore `properpcloud` does **not** reverse-engineer or patch the proprietary application. It builds a small source-neutral player around:

1. a folder/file domain model;
2. a native pCloud adapter using the official SDK;
3. Media3 playback with renewable stream URLs;
4. optional WebDAV and Android DocumentsProvider adapters;
5. separately upstreamable improvements to the SDK and suitable FOSS players.

## User-facing contract

The first useful release must provide:

- browse pCloud by folder;
- enqueue one folder or a recursive subtree;
- jump from the player back to the containing folder;
- sort by natural filename, disc/track number, title, or modification time;
- persist progress for long recordings and audiobooks;
- whitelist or blacklist source folders;
- inspect raw filename, path, pCloud metadata, detected tags, and effective sort keys.

Metadata repair and remote tag writing are deliberately later features because they mutate user files and require careful revision/rollback handling.

## Repository state

This bootstrap contains a verified multi-module Android project, a Media3 playback service seed, a source-neutral queue model, and a native pCloud adapter for listing folders and resolving direct stream links. Authentication UI and the production browser are the next implementation slice.

The code is accompanied by a normative YAML specification suite covering product scope, use cases, architecture, interfaces, UX, tests, build rules, release DoDs, and the Linux client. The Docker-backed `make ci` gate passes; exact toolchain, test, lint, image, and APK evidence is recorded in `docs/verification.md`.

## Structure

```text
properpcloud/
├── app/                    Android shell and Media3 service
├── core-model/             source-neutral file, queue, sort, and stream contracts
├── source-pcloud/          official pCloud SDK adapter
├── source-webdav/          fallback/interoperability configuration
├── docs/
│   ├── architecture.md
│   ├── system-design.md
│   ├── linux-client.md
│   ├── overlay-build-review.md
│   ├── verification.md
│   ├── research.md
│   ├── roadmap.md
│   ├── upstreaming.md
│   └── adr/
├── spec/                   normative YAML contracts and DoDs
├── Dockerfile
├── compose.yaml
├── Makefile
├── build.gradle.kts
├── settings.gradle.kts
└── bridge.yml
```

## Build

Requirements:

- JDK 17 or newer supported by the selected Gradle release;
- Docker with BuildKit;
- Android SDK platform 36 and build tools 36.0.0 are supplied by the project image;
- host `adb` only when installing to a device.

```sh
make image
make doctor
make test
make lint
make build
```

The build runs through the committed Gradle Wrapper inside the pinned Android toolchain container. Host `adb` remains responsible for device installation through `make install`.

No pCloud credentials are needed for compilation or unit tests. Runtime OAuth setup is documented in `docs/architecture.md`.

## Design index

- `spec/manifest.yml` — normative specification index and invariants
- `spec/product.yml` — complete feature set and quality attributes
- `spec/use-cases.yml` — user journeys, alternatives, and acceptance references
- `spec/architecture.yml` — layers, module graph, data flows, and security boundaries
- `spec/contracts.yml` — ports, commands, records, events, persistence, and errors
- `spec/ux.yml` — Android and desktop interaction and accessibility design
- `spec/testing.yml` — fixtures, fault injection, contract suites, and release gates
- `spec/definition-of-done.yml` — feature and release completion rules
- `spec/build.yml` — Docker, Gradle, CI, cache, and supply-chain contract
- `spec/linux-client.yml` — desktop reuse and implementation plan
- `docs/system-design.md` — architecture narrative
- `docs/linux-client.md` — desktop design narrative
- `docs/overlay-build-review.md` — inspected strengths and corrected weaknesses
- `docs/verification.md` — exact build, test, lint, image, and APK evidence

## Licensing

Original code in this repository is Apache-2.0. This keeps the source adapters reusable by Apache-, GPL-, and AGPL-licensed upstream projects. Code copied from a copyleft player must remain in a separately licensed module or fork; none is copied in this bootstrap.
