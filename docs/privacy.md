# Privacy model

properpcloud is a local client. It has no mandatory project-operated backend,
advertising SDK, analytics SDK, telemetry upload, or account database.

## Data processed locally

- provider source and node identifiers;
- folder and filename metadata returned by the selected source;
- queue order and containing-folder references;
- playback position, duration, completion, and selected sort/source settings;
- a bundled or user-overridden public pCloud application client ID;
- an OAuth access token after successful pCloud authorization, or an interim
  direct-login `auth` token after successful documented account authentication;
- local embedded-tag snapshots, approved patch plans, and staged-file hashes when
  the user invokes metadata tooling.

The token is encrypted with an Android Keystore AES-GCM key. App data, token
storage, and preferences are excluded from Android cloud backup and device
transfer.

## Network communication

When the demo source is selected, no network is required.

When pCloud is selected, the app communicates with pCloud's documented regional
API host and temporary content hosts returned by pCloud. The app accepts only
`api.pcloud.com` or `eapi.pcloud.com` as the account API host.

OAuth is preferred and keeps the password on pCloud's page. Builds configured with
properpcloud's registered public application ID present OAuth as the ordinary path.
The optional fallback direct-login form sends the entered email and password once
to the explicitly selected regional `userinfo`
endpoint over HTTPS POST. The password is removed from Compose form state before
the request starts, mutable buffers are cleared after use, and no password is
written to preferences, backup, files, logs, analytics, crash reports, or release
evidence. An immutable runtime string can remain until garbage collection; the app
does not claim impossible process-memory zeroization.

Temporary signed media links are capabilities. They are resolved immediately
before playback, kept only in Media3 runtime state, and not written to DataStore,
logs, queue snapshots, release evidence, or bug-report templates.

Queue and progress persistence uses stable source/node identity, containing-folder
identity, position, duration, playback speed, observation time, and completion.
The frozen cross-platform fixture corpus contains the same non-secret fields and
deliberately excludes stream URLs, tokens, provider responses, and local paths.

Online metadata matching is opt-in. A MusicBrainz search may transmit the title,
artist, album, ISRC, and approximate duration that the user approved. A future
AcoustID lookup may transmit a locally derived Chromaprint fingerprint and
duration when an application key is configured. These lookup paths do not upload
audio bytes. Candidate results remain proposals and cannot trigger a remote write.

Tag studio source copies stay in app-private cache and are removed when the editor
closes; stale copies are additionally bounded by age and count. Verified exports
stay in app-private files until the user shares or saves them through a temporary
read-only `FileProvider` grant. ZIP manifests contain filenames, changed fields,
and hashes—not OAuth tokens, signed URLs, app-private paths, fingerprints, or
unrestricted provider responses.

## Data not collected

properpcloud does not persist or centrally collect:

- a pCloud account password—the interim fallback handles it transiently only for
  the direct provider request, while OAuth does not expose it to properpcloud;
- advertising identifiers;
- contacts, location, camera, microphone, or phone state;
- usage analytics or crash telemetry;
- private media files for project-operated processing;
- audio bytes for MusicBrainz, Cover Art Archive, or AcoustID lookup;
- metadata provider API keys in source code or release artifacts;
- the pCloud client secret in source code, Gradle, Docker environments, client
  binaries, CI variables, or release artifacts;
- credentials for public CI.

## User controls

- **Use demo** switches to an entirely local source.
- **Disconnect** removes the encrypted pCloud session from this device.
- Clearing app storage removes preferences, queue/progress records, generated
  demo media, metadata source copies and exports, and local credential material.

## External services

pCloud's own privacy policy and infrastructure apply when the user authorizes a
pCloud account or streams pCloud content. GitHub applies its own policies when a
user downloads releases or participates in the repository.

## Reporting privacy issues

Use GitHub private vulnerability reporting. Revoke exposed credentials before
reporting, and never paste tokens, signed URLs, private filenames, or account
media into a public issue.
