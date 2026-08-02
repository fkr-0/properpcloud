# pCloud adapter API

Package: `dev.properpcloud.source.pcloud`

## PCloudSession

```kotlin
data class PCloudSession(
    val accessToken: String,
    val apiHost: String,
    val userId: Long,
    val tokenKind: PCloudTokenKind = PCloudTokenKind.OAUTH_BEARER,
)
```

Allowed hosts are `api.pcloud.com` and `eapi.pcloud.com`. The token is redacted from `toString` and must remain in a platform credential store.

`PCloudSourceFactory.create(session)` chooses the correct SDK authenticator for OAuth bearer or legacy authentication tokens and returns `PCloudAudioSource`.

## Node IDs

`PCloudNodeIds.folder(id)` and `PCloudNodeIds.file(id)` encode provider type and numeric ID. `parse` rejects malformed and type-confused identifiers. Do not pass raw provider numbers through the core API.

## PCloudAudioSource

The source adapter:

- lists folders through the official Java core SDK;
- filters non-audio files by MIME type or recognized extension;
- maps provider timestamps and sizes into core records;
- resolves a fresh best direct URL for playback;
- exposes provider inspection fields without returning credentials;
- implements verified metadata staging downloads.

## Verified metadata download

`prepareMetadataSource` performs:

1. provider snapshot with size, provider hash, modified time, and SHA-256;
2. download into a non-existing destination;
3. local size and SHA-256 verification;
4. second provider snapshot;
5. revision equality check;
6. return of `PreparedMetadataSource` evidence.

Any failure deletes the destination file.

## Direct login

`PCloudDirectLoginClient.signIn(email, password, region)` returns a sealed `PCloudDirectLoginResult`. Input, response, token, and body sizes are bounded. Cancellation is preserved, network failures are classified, and the supplied password array is overwritten in `finally`.
