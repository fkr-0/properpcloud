# Linux client

The Linux client is implemented as native desktop software over the shared Kotlin/JVM core. It does not reuse the Android application, require Waydroid, or depend on a mounted pCloud filesystem.

The normative contract remains [`../spec/linux-client.yml`](../spec/linux-client.yml). Installation and daily use are covered in the [Linux user manual](user-manual/linux-installation.md); class-level details are in the [desktop API reference](api/desktop.md).

## Implemented runtime

```text
Compose Desktop
      |
DesktopController
      |
      +-- shared core model, queue, sorting, progress, resume
      +-- portable pCloud java-core adapter
      +-- deterministic generated-WAV demo source
      +-- SQLite queue, settings, and progress
      +-- Secret Service session-token vault
      +-- mpv child process over private JSON IPC
      +-- MPRIS root/player interfaces over session D-Bus
```

## Current feature set

- three-pane folder, library/inspection, and queue layout;
- stable-ID folder navigation and breadcrumbs;
- deterministic direct and recursive folder queues;
- play-now, play-next, append, select, remove, and reorder operations;
- fresh pCloud stream resolution before every playback load;
- mpv play, pause, seek, next, previous, duration, and position state;
- smart resume from durable SQLite progress;
- queue and source restoration after restart;
- pCloud direct sign-in with explicit EU/US region selection;
- pCloud session token stored through freedesktop Secret Service;
- MPRIS play, pause, next, previous, seek, status, and metadata;
- XDG config, data, cache, and runtime locations;
- deterministic headless smoke test with a real mpv process;
- Compose Desktop application-image and `.deb`/`.rpm` packaging configuration.

## Shared versus platform-specific code

| Concern | Shared | Android | Linux |
| --- | --- | --- | --- |
| Source/node identity | `core-model` | — | — |
| Folder traversal and sorting | `core-model` | — | — |
| Queue reducer and snapshot | `core-model` | — | — |
| Progress and resume policy | `core-model` | — | — |
| pCloud mapping and streams | `source-pcloud` | — | — |
| User interface | — | Jetpack Compose | Compose Desktop |
| Playback | port semantics | Media3 | mpv JSON IPC |
| Durable state | logical records | DataStore | SQLite JDBC |
| Credentials | session contract | encrypted app storage | Secret Service |
| Media integration | state semantics | MediaSession | MPRIS |

The pCloud, WebDAV, metadata-online, and metadata-tags modules compile as ordinary JVM modules. They contain no Android imports and can be reused by both clients.

## mpv process contract

The application owns one controlled child process:

```text
mpv
  --no-config
  --idle=yes
  --terminal=no
  --audio-display=no
  --force-window=no
  --input-ipc-server=$XDG_RUNTIME_DIR/properpcloud/mpv-<pid>.sock
```

Safety and correctness invariants:

- `ProcessBuilder` receives an argument list; no shell interpolation is used;
- user mpv configuration is ignored for deterministic behavior;
- the socket lives below a private user runtime directory;
- only `https:` and `file:` handles are accepted;
- the provider URL exists only in memory and the IPC request;
- mpv output is discarded so a signed URL cannot enter logs;
- connect, write, and response waits are bounded;
- queue and progress remain authoritative in SQLite;
- closing the application terminates mpv and removes the socket.

The smoke entry point adds `--ao=null`, allowing real IPC verification without an audio device.

## Persistence

The desktop schema is intentionally small:

```text
settings(key, value)
queue_entries(position, source_id, node_id, origin_id)
progress(source_id, node_id, position_ms, duration_ms, speed, observed_ms, completed)
```

It uses WAL mode and transactional full-queue replacement. Signed stream links, account passwords, and provider command lines are forbidden from all tables.

XDG locations:

```text
$XDG_CONFIG_HOME/properpcloud
$XDG_DATA_HOME/properpcloud/properpcloud.db
$XDG_CACHE_HOME/properpcloud
$XDG_RUNTIME_DIR/properpcloud
```

## Account handling

The current desktop account UI uses the same bounded direct sign-in client as Android. The user selects exactly one regional pCloud HTTPS endpoint. The password is supplied as a mutable array and overwritten by the authentication client. Only the returned session token and regional host are stored in Secret Service.

Browser OAuth remains the preferred long-term route. Implementing it requires a confirmed desktop redirect registration in the pCloud application console; the desktop client does not invent or embed a client secret.

## Desktop interaction

```text
┌───────────────┬─────────────────────────────┬─────────────────────┐
│ breadcrumbs   │ current folder              │ queue               │
│ inspector     │ folder and track actions    │ reorder/remove      │
├───────────────┴─────────────────────────────┴─────────────────────┤
│ title · transport · ± seek · timeline · position                │
└──────────────────────────────────────────────────────────────────┘
```

The current UI provides menu equivalents for queue operations. Drag-and-drop, richer keyboard focus navigation, desktop notifications, saved roots, and a detachable mini-player remain post-parity refinements rather than prerequisites for the implemented playback path.

## Verification

```bash
make linux-ci
```

The aggregate target runs `desktop-test`, `desktop-package`, `desktop-smoke`, and `desktop-mpris-smoke`. The unit suite covers XDG mapping, SQLite round trips, deterministic demo traversal and media generation, and mpv command encoding. The playback smoke covers the actual host Unix socket and mpv process. The MPRIS smoke verifies the packaged jlink runtime and externally queries root/player properties over an isolated session bus. `.github/workflows/linux.yml` installs the explicit host dependencies and runs the same gate on pull requests and `main`.

## Remaining release gates

Before declaring the Linux `0.2.0` release complete:

1. run interactive pCloud tests against protected EU and US test accounts;
2. verify Secret Service behavior under GNOME, KDE Plasma, and an i3 keyring session;
3. exercise MPRIS through common media-key daemons;
4. validate long playback, expired direct-link recovery, mpv crash recovery, and process restart;
5. produce reproducible Arch and broad-distribution artifacts;
6. perform keyboard-only, high-contrast, font-scaling, and accessibility review;
7. add browser OAuth after provider redirect registration is confirmed.

The implementation is therefore functional and tested locally, while distribution and protected live-provider evidence remain release gates.
