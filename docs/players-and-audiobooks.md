# Players overview and audiobook mode

## Players

The Android Players destination is a source-neutral projection over the existing playback
authority. The local row is the existing Media3 controller; the feature does not construct a
second player or media session.

Providers may contribute stable PlayerTargetSnapshot records. Records are merged by stable
target ID, so rediscovery or reconnect updates the same row instead of duplicating it. When a
previously known target disappears or its provider fails, the target remains visible as stale,
degraded, or unavailable rather than silently looking healthy.

The optional server provider uses GET /api/v1/players when the connected catalog server exposes
that capability. A 404 means the capability is absent and contributes no remote rows. Remote
server rows are observe-only until that provider exposes a controller authority; the Android app
does not synthesize remote transport controls.

No bearer token, signed stream URL, or provider password is copied into player snapshots or logs. Normal Settings and playback surfaces also avoid exposing credential-storage implementation details; security behavior remains enforced underneath the UI.

## Audiobook mode

Built-in audiobook tabs use PlaybackContentMode.AUDIOBOOK. Music tabs retain the existing music
queue behavior. In audiobook mode:

- shuffle and music repeat are forced off at the playback authority;
- previous restarts the current chapter after five seconds, otherwise it moves to the previous
  chapter; next moves to the next chapter;
- rewind and forward intervals are presented independently and may be adjusted in the full player; user choices are saveable across Activity recreation and Android saved-state process restoration;
- playback speed is retained per tab;
- a durable resume record is keyed by source ID plus book folder ID and stores chapter ID,
  position, duration, speed, and update time;
- replacing the queue with a book folder restores its stored chapter and position when that chapter
  still exists;
- automatic chapter advance can be stopped at the chapter boundary, while explicit chapter
  navigation remains allowed; manual intent is bound to one expected media transition and expires
  quickly so a no-op/restart command cannot suppress a later automatic boundary stop;
- sleep timer deadline is stored with the tab and is restored after Activity/service/process
  recreation. Expired restored timers are cleared without starting playback;
- all queue/tab restoration calls the existing controller with play=false. Restoration therefore
  never implies autoplay.

The compact and full player expose the active player. Audiobook playback additionally exposes the
book/chapter context, speed, resume position, end-of-chapter behavior, and sleep timer state.
