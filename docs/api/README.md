# API reference

This reference documents the stable source-neutral contracts used by both Android and Linux. Package names are authoritative; concrete UI classes remain implementation details unless listed here.

## Packages

| Package | Purpose |
| --- | --- |
| `dev.fkr.properpcloud.core.model` | Stable identities, media nodes, sources, queue, sorting, and progress policy. |
| `dev.fkr.properpcloud.source.pcloud` | pCloud node mapping, session creation, source adapter, and verified metadata download. |
| `dev.fkr.properpcloud.source.webdav` | Portable WebDAV endpoint contract. |
| `dev.fkr.properpcloud.metadata.tags` | Embedded tag parsing and mutation boundary. |
| `dev.fkr.properpcloud.metadata.online` | Online metadata lookup boundary and provider implementations. |
| `dev.fkr.properpcloud.desktop.*` | Linux platform adapters and orchestration. |

## Contract pages

- [Core media and source API](core-media.md)
- [Queue and sorting API](queue-and-sorting.md)
- [Progress API](progress.md)
- [pCloud adapter API](pcloud.md)
- [Desktop platform API](desktop.md)
- [Persistence schema](persistence.md)

## Stability rules

- `SourceId` and `NodeId` string formats are persisted and must remain backward-compatible.
- `AudioSource` methods are suspend functions and may perform network or disk I/O.
- A `StreamHandle` is transient and must not be used as an identity key.
- Queue reducer operations are pure and deterministic.
- New persisted fields require migration fixtures and compatibility tests.
