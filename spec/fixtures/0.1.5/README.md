# properpcloud 0.1.5 compatibility fixtures

These files freeze the source-neutral queue and playback-progress persistence
semantics that Android `0.1.5` hands to the native Linux `0.2.0` implementation.

- `queue.json` is the exact compact JSON stored under `queue_json`.
- `queue-index.txt` is the integer stored under `queue_index`.
- `progress.json` is the exact compact JSON stored under `progress_json`.
- Queue entries persist stable source/node/origin IDs only—never stream URLs.
- Progress keys join source ID and node ID with U+001F; JSON escapes it as
  `\u001f`.
- Unknown additional object fields must be ignored by future readers, while
  missing required identity/timing fields are invalid.

The Android test suite decodes these fixtures and reproduces their bytes. Linux
`0.2.0` must run the same corpus before claiming queue/progress parity.
