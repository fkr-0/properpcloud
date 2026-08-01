# ADR 0002: Keep the player source-neutral

- Status: accepted
- Date: 2026-08-01

## Context

A pCloud-only domain model would make WebDAV, local files, SAF, or future providers expensive. A generic cloud filesystem abstraction can also become too large and leaky.

## Decision

The core knows only stable nodes, folders, audio tracks, stream capabilities, and inspection fields. Provider-specific operations remain in optional capability interfaces and adapter modules.

## Consequences

- pCloud file IDs remain opaque outside the adapter;
- direct URLs are renewable playback capabilities, not identity;
- queue, progress, filter, and sort logic are provider-independent;
- provider-specific advanced functionality can evolve without bloating the minimum interface.
