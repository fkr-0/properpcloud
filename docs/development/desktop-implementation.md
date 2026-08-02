# Desktop implementation

## Runtime graph

```text
Compose Desktop UI
       |
DesktopController
  |        |          |             |
AudioSource Queue   SQLite      Secret Service
  |        policy    state       session token
pCloud or demo
       |
StreamHandle
       |
mpv child process <---- JSON IPC ----> MpvController
       |
MPRIS snapshot over session D-Bus
```

## Main components

| Class | Responsibility |
| --- | --- |
| `DesktopController` | Coordinates source browsing, queue mutations, progress, playback, account state, and MPRIS snapshots. |
| `DesktopDemoAudioSource` | Deterministic credential-free media tree and generated WAV fixtures. |
| `SqliteStateRepository` | WAL-backed settings, ordered queue, and progress records. |
| `SecretServiceVault` | Session-token storage through `secret-tool`, with secrets on standard input rather than argv. |
| `MpvController` | Safe child-process launch and bounded Unix-domain JSON IPC. |
| `MprisService` | `org.mpris.MediaPlayer2` root and player interfaces. |
| `XdgPaths` | Platform storage and private runtime-directory resolution. |

## mpv invariants

- launch with `ProcessBuilder(List<String>)`, never a shell command;
- always pass `--no-config`;
- create one process-specific IPC socket below the private runtime directory;
- accept only `https:` and `file:` stream handles;
- never write a provider URL to disk;
- discard mpv standard output and error;
- bound connect, write, and response waits;
- preserve queue and progress when mpv exits.

## State restoration

On startup the controller:

1. opens and migrates the SQLite schema;
2. attempts to restore a pCloud session from Secret Service;
3. selects the last source when available, otherwise the demo source;
4. loads the source root;
5. resolves persisted queue node references against currently available sources;
6. restores the current queue index without auto-playing.

Missing or deleted provider nodes are skipped. A signed URL is never required for restoration.

## Headless smoke entry point

`MainKt --smoke` avoids initializing Compose. It creates isolated temporary XDG roots, generates WAV media, recursively queues a nested demo folder, persists the queue, starts mpv with null audio output, checks IPC state, persists progress, and cleans up.
