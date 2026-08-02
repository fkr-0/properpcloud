# properpcloud documentation

properpcloud is a folder-first audio player for pCloud libraries. It treats cloud folders as the primary catalog, builds deterministic playback queues without requiring complete tags, and keeps signed provider URLs out of persistent state.

## Choose a starting point

| Goal | Start here |
| --- | --- |
| Install and use the application | [User manual](user-manual/README.md) |
| Run the Linux desktop client | [Linux installation](user-manual/linux-installation.md) |
| Connect a pCloud account safely | [Accounts and security](user-manual/accounts-and-security.md) |
| Build or contribute | [Developer guide](development/README.md) |
| Integrate a new source or persistence adapter | [API reference](api/README.md) |
| Understand trust boundaries | [Architecture](architecture.md) and [privacy](privacy.md) |

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

## Documentation as code

All published pages originate as Markdown under `docs/`. The static documentation site synchronizes those files into an Astro Starlight build and deploys the generated HTML through GitHub Pages.
