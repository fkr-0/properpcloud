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
```

Queue saves replace the ordered snapshot inside one transaction. Progress uses an upsert on stable media identity.

## Android logical schema

Android currently stores equivalent queue and progress records through DataStore JSON. The physical representation differs, but field meanings and migration fixtures are shared at the contract level.

## Forbidden persisted data

- signed or expiring stream URLs;
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
