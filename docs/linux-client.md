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
- fresh pCloud stream resolution before every playback load and one bounded capability refresh after an unexpected stream failure;
- mpv play, pause, seek, next, previous, duration, position, EOF, stream-failure, and process-exit state;
- smart resume from durable SQLite progress;
- queue and source restoration after restart;
- pCloud direct sign-in with explicit EU/US region selection;
- pCloud session token stored through freedesktop Secret Service;
- MPRIS play, pause, next, previous, seek, status, and metadata;
- XDG config, data, cache, and runtime locations;
- deterministic headless smoke test with a real mpv process;
- complete keyboard alternatives for library and queue selection, play, append, inspect, reorder, and removal;
- Compose Desktop application-image, AppImage, Flatpak, and immutable-source Arch packaging gates.

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
- every IPC command carries a request ID and ignores unrelated event/response lines;
- response data is accepted only for an explicit successful matching command;
- queue and progress remain authoritative in SQLite;
- an unexpected active-to-idle transition that is neither EOF nor explicit stop is a fixed redacted stream failure;
- one fresh capability may be resolved per stable media identity and cooldown window, resuming at durable progress;
- an unexpected process exit never causes an automatic process restart and requires an explicit restart action;
- closing the application terminates mpv and removes the socket.

The smoke entry points add `--ao=null`, allowing real IPC, process-recovery, and bounded resilience-soak verification without an audio device.

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

The current desktop account UI uses the same bounded direct sign-in client as Android. The user selects exactly one regional pCloud HTTPS endpoint. The password is supplied as a mutable array and overwritten by the authentication client. Only the returned session token and regional host are stored in Secret Service. Disconnect removes the active source and pCloud queue locally first and persists a disconnect tombstone before clearing Secret Service and attempting token-kind-aware remote revocation. A stale credential therefore cannot be restored after an interrupted cleanup, and an unconfirmed remote result cannot restore local access.

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

The current UI provides pointer and keyboard equivalents for every queue operation. `Ctrl+L` and `Ctrl+Q` select visible library or queue focus; arrow keys select, Enter opens or plays, Shift/Ctrl/Alt+Enter expose append/replace/inspect, and Alt+Up/Down plus Delete reorder or remove queue items. F1 documents the full map, selected rows remain visible, and account dialogs suppress global playback shortcuts. Drag-and-drop, desktop notifications, saved roots, and a detachable mini-player remain post-parity refinements rather than prerequisites for the implemented playback path.

## Verification

```bash
make linux-ci
```

The aggregate target runs desktop unit/package checks, the normal real-mpv/SQLite smoke, a forced crash/recovery smoke, the packaged clean-profile smoke, externally driven MPRIS controls, an isolated locked-keyring gate, and 200% high-contrast capture. The crash path detects unexpected process exit, performs no automatic process restart, preserves the selected stable queue identity, and resumes only after an explicit action. Stream failure is classified separately and permits one bounded capability re-resolution with durable resume. The unit suite covers XDG mapping, SQLite round trips, deterministic demo traversal and media generation, request-correlated mpv commands, EOF/stop/failure classification, bounded Secret Service lookup, keyboard shortcuts, sleep-transition policy, and fixed exit states. `make desktop-session-audit` probes disposable Secret Service storage, all nine MPRIS methods, and the packaged logind subscription on the real user session; `make desktop-locked-keyring-smoke` uses an ephemeral locked keyring; `make desktop-accessibility-audit` retains 1280×820 base/help captures at 200%; `make desktop-resilience-soak` exercises pause/seek/checkpoint and controlled recovery; `make arch-package-gate` clean-builds and installs the immutable source archive in Arch. `.github/workflows/linux.yml` retains the credential-free baseline on pull requests and `main`.

## Release train to 0.2.0

- **0.1.8 runtime hardening:** shared stale-queue repair, selection preservation,
  repaired snapshot rewrite, forced final checkpoints, correlated/redacted mpv IPC,
  local-first remote revocation, bounded Secret Service processes, and an allowlisted
  Flatpak host-player bridge.
- **0.1.9 compatibility hardening:** published immutable AppImage/Flatpak evidence,
  explicit process recovery with zero automatic process restarts, exact release graph,
  shared bounded capability refresh, tested keyboard alternatives, and executable Arch,
  session, soak, and readiness gates.
- **0.2.0 parity promotion:** complete alternate desktop, physical media-key,
  physical suspend, and screen-reader observations; retain protected EU/US provider expiry
  evidence; and keep the documented direct-sign-in fallback while desktop OAuth registration
  remains unconfirmed. No additional feature tranche is hidden in the release.

## Remaining release gates

Before declaring the Linux `0.2.0` release complete, the `0.1.8` and `0.1.9`
evidence manifests must be complete and the following protected gates must pass:

1. repeat the immutable Arch clean-build/install smoke against the exact `v0.2.0` archive after tagging;
2. retain a four-hour protected-provider soak with genuine capability expiry and one real suspend/resume cycle;
3. run interactive pCloud browse/playback/expiry/disconnect/restart checks against protected EU and US accounts;
4. observe one physical media-key path and one real suspend/resume cycle, and complete GNOME/KDE sessions;
5. perform a real AT-SPI screen-reader traversal/action review;
6. run `python3 scripts/validate-020-readiness.py --pre-tag` before the tag, then rerun with `--strict` after exact-tag package evidence; explicitly document any narrow accepted exception;
7. add browser OAuth only after provider redirect registration is confirmed.

The implementation gap is now narrow and measurable. The canonical state is `docs/reviews/0.2.0-promotion-matrix.yml`; protected live-provider and alternate-session evidence remain release gates rather than implementation guesses.
