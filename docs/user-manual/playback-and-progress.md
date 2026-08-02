# Playback and progress

## Platform playback engines

- Android uses Media3 ExoPlayer in a playback service.
- Linux uses an mpv child process controlled through line-delimited JSON over a private Unix-domain socket.

Both engines receive a fresh stream handle from the source adapter immediately before loading a track.

## Resume behavior

Progress records contain stable source/node identity, position, optional duration, playback speed, observation time, and completion state.

Before resuming, the policy applies a smart rewind:

- short interruption: rewind 5 seconds;
- interruption of at least 30 minutes: rewind 15 seconds;
- track at or beyond 95%: mark complete instead of resuming near the end.

The desktop client checkpoints approximately every five seconds while a track is active and writes queue changes immediately.

## Expiring stream links

pCloud direct links can expire. The application treats them as capabilities, not identities:

1. resolve immediately before loading;
2. keep the URL only in process memory and the mpv command channel;
3. preserve the last confirmed position;
4. resolve a replacement link when retry policy permits;
5. resume from the preserved position.

## Linux controls

| Input | Action |
| --- | --- |
| Space | Play or pause when text input does not consume the key. |
| Ctrl + Right | Next queue entry. |
| Ctrl + Left | Previous queue entry. |
| Player arrows | Rewind 15 seconds or advance 30 seconds. |
| Desktop media keys | Routed through MPRIS when the desktop session supports them. |

The Linux client invokes mpv without a shell and with `--no-config`. Provider URLs are never written to playlist files or child-process logs.
