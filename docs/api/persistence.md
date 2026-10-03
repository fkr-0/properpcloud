# Persistence schema

## Linux SQLite schema

Database location: `$XDG_DATA_HOME/properpcloud/properpcloud.db`

```sql
CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE queue_entries (
    position INTEGER PRIMARY KEY,
    source_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    origin_id TEXT NOT NULL
);

CREATE TABLE progress (
    source_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    position_ms INTEGER NOT NULL,
    duration_ms INTEGER,
    speed REAL NOT NULL,
    observed_ms INTEGER NOT NULL,
    completed INTEGER NOT NULL,
    PRIMARY KEY (source_id, node_id)
);

CREATE TABLE playback_history (
    source_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    position_ms INTEGER NOT NULL,
    duration_ms INTEGER,
    observed_ms INTEGER NOT NULL,
    completed INTEGER NOT NULL,
    PRIMARY KEY (source_id, node_id)
);
```

Queue saves replace the ordered snapshot inside one transaction. Progress uses an upsert on stable media identity. `playback_history` is an additive, optional facility: it is disabled by default, defaults to 100 retained identities when enabled, and is hard-limited to 500. A history update replaces the previous row for the same `(source_id, node_id)` and trims older rows after a checkpoint, so it cannot grow without bound.

Desktop settings also persist the filename-search match-type set and the history enable/retention controls. Search query text itself is transient UI state.

## Android logical schema

Android stores equivalent queue and progress records through DataStore JSON and preferences. Queue JSON contains only `source`, `node`, and `origin` stable identities plus a separate current index. Progress JSON is keyed by stable source/node identity. Additive preferences hold the filename-search match-type set, history enable flag, history retention, and bounded history JSON.

Every successful queue add/remove/reorder/replace/clear operation goes through the centralized queue commit boundary. Player-driven current-item changes are also written back by stable media identity. Interactive saves are serialized in invocation order; startup queue repair is written before repaired state is treated as authoritative. If a user mutates the queue while startup restoration is still resolving, that newer mutation wins.

Startup restoration loads and normalizes the current track's saved position before installing the queue into the platform controller. The queue/current item and start position therefore enter the player together in a paused/no-autoplay state instead of briefly installing position zero and risking a false checkpoint.

## Forbidden persisted data

- signed or expiring stream URLs;
- `StreamHandle` objects or provider response bodies;
- account passwords;
- plaintext provider tokens;
- absolute temporary staging paths as durable identity;
- child-process command lines.

## Migration requirements

A persisted format change must include:

1. versioned migration logic;
2. old-format fixture under `spec/fixtures/`;
3. round-trip and upgrade tests;
4. rollback or forward-recovery behavior;
5. user-facing release notes when data semantics change.

The history addition is backward compatible: an older Desktop SQLite database receives the missing table with `CREATE TABLE IF NOT EXISTS` while its queue and progress rows remain intact. Android's new DataStore keys are optional defaults, and malformed optional queue/progress JSON is ignored or repaired rather than promoted into invalid stable identity.
