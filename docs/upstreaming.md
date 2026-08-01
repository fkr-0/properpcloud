# Upstreaming strategy

The highest-yield path is to avoid beginning with a permanent full fork.

## pCloud SDK

Potential upstream contributions should be small and provider-specific:

- coroutine-friendly `Call.await()` extensions if the current KTX module lacks the needed behaviour;
- explicit stream-link renewal examples for Media3/ExoPlayer;
- documented regional OAuth/token persistence sample;
- tests and documentation for long-running range requests;
- a change-feed wrapper around `diff` if absent from the Java SDK.

Do not ask pCloud to merge UI or player policy into the SDK.

## Symphony

Symphony already values filename/path sorting, folder views, and queue control. It is a good reference and a possible later host for generic source abstractions. A viable upstream sequence is:

1. propose a source-neutral node/stream boundary without mentioning pCloud-specific UI;
2. preserve the existing local MediaStore source as the default implementation;
3. add remote-source capability behind a build feature or plugin boundary;
4. keep pCloud adapter code in this Apache-2.0 repository.

Because Symphony is AGPL-3.0, any direct derivative app remains AGPL. No Symphony code is copied here.

## Voice

Voice is the strongest reference for progress, rewind, bookmarks, speed, and audiobook UX. Its local-only product philosophy may make direct remote-source upstreaming undesirable. Prefer extracting generally useful player behaviour or interoperating through DocumentsProvider before proposing a cloud-specific feature.

## auDAV

auDAV may provide the fastest audiobook-only WebDAV result. Test pCloud's EU/US WebDAV endpoints against it. Upstream generic fixes for server compatibility, folder ordering, and credentials; avoid adding pCloud branding unless maintainers want provider presets.

## Android ecosystem contribution

A pCloud-backed `DocumentsProvider` is independently valuable. It would expose pCloud to any SAF-aware application, not only this player. It does not replace the integrated player because external apps may still lack folder enqueue and long-form progress, but it creates a broadly upstreamable interoperability layer.
