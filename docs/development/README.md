# Developer guide

properpcloud is a Gradle multi-project build with a source-neutral Kotlin/JVM core, portable provider and metadata adapters, and platform shells for Android and Linux.

## Repository map

```text
app/                 Android application and platform adapters
desktop-app/         Compose Desktop, mpv, SQLite, Secret Service, MPRIS
core-model/          source-neutral domain records and policies
source-pcloud/       portable pCloud Java SDK adapter
source-webdav/       portable WebDAV boundary
metadata-tags/       embedded metadata toolkit
metadata-online/     online metadata provider boundary
docs/                canonical Markdown manual and API documentation
website/             Astro Starlight static documentation renderer
spec/                machine-readable product, architecture, and test contracts
scripts/             reproducible build and verification helpers
```

## Supported workflow

```bash
make doctor
make test
make lint
make build
make docs-build
make desktop-smoke
```

The Android and JVM compilation environment is containerized. The host smoke test deliberately runs the desktop distribution against the host's real mpv and Unix socket implementation.

## Design constraints

1. Domain and application code must not import Android classes.
2. Provider identity is `(SourceId, NodeId)`, never a URL or filename.
3. Source adapters resolve stream capabilities at the last responsible moment.
4. Queue construction must be deterministic, cancellable, and explicit about omissions.
5. Metadata writes require revision and content verification.
6. Platform credential stores own long-lived secrets.
7. New behavior updates code, tests, Markdown documentation, and the relevant `spec/*.yml` contract.

## Further reading

- [Build and test](build-and-test.md)
- [Desktop implementation](desktop-implementation.md)
- [Documentation and Pages](documentation-site.md)
- [Release process](release-process.md)
- [API reference](../api/README.md)
