# Metadata inspection and repair

properpcloud separates playback identity from embedded tags. A badly tagged file can still be browsed and played using its provider filename and stable node ID.

## Inspection

Select an item and choose **Inspect** to view read-only information such as:

- provider and stable IDs;
- parent folder;
- content type and size;
- modified time and provider hash;
- embedded title, artist, album, track, disc, and duration when available.

Inspection does not download or rewrite the complete file unless a metadata workflow explicitly requires a local staging copy.

## Repair workflow

Metadata writes follow a staged transaction:

```text
provider snapshot
    -> verified local download
    -> parse and propose changes
    -> user review
    -> write staged copy
    -> verify output
    -> guarded provider replacement
    -> retain rollback evidence
```

The staging download is accepted only when size, provider revision, and SHA-256 remain consistent before and after transfer.

## Online suggestions

Online metadata providers can suggest candidates, but suggestions are evidence rather than authority. A match should show the queried recording, confidence signals, and field-level changes before acceptance.

## Safety rules

- Never infer provider media identity from a title or filename.
- Never perform silent bulk writes.
- Never replace a provider file after its observed revision changes.
- Never discard the original until replacement verification succeeds.
- Keep ordinary playback functional when metadata parsing fails.
