# Research findings

Snapshot: 2026-08-01.

## 1. Is the official Android app open source?

No public source repository for the official `com.pcloud.pcloud` Android application was found.

pCloud's official GitHub organisation publishes SDKs, a console client, filesystem/sync libraries, and several other components. Its repository list does not expose the production Android application. This is evidence that the app is not publicly forkable, not proof about pCloud's private internal repository.

Relevant sources:

- <https://github.com/pCloud>
- <https://play.google.com/store/apps/details?id=com.pcloud.pcloud>
- <https://www.pcloud.com/release-notes/android>

Conclusion: adapting the shipped Android application is not a viable open-source strategy unless pCloud publishes it or grants source access.

## 2. Is the API usable for an independent client?

Yes.

pCloud documents:

- HTTP/JSON and binary APIs;
- OAuth 2.0 bearer authentication;
- separate US and EU API hosts;
- 64-bit file and folder identifiers;
- `listfolder` for direct or recursive trees;
- `getfilelink` for expiring direct content URLs;
- `diff` for incremental filesystem changes;
- upload, rename, revision, thumbnail, collection, and public-link methods.

The official Java/Android SDK is Apache-2.0 licensed. Maven Central currently publishes version 1.11.0; it exposes `ApiClient.listFolder`, `loadFile`, `createFileLink`, and Android authorization support.

Relevant sources:

- <https://docs.pcloud.com/>
- <https://docs.pcloud.com/methods/oauth_2.0/>
- <https://docs.pcloud.com/methods/folder/listfolder.html>
- <https://docs.pcloud.com/methods/streaming/getfilelink.html>
- <https://docs.pcloud.com/methods/general/diff.html>
- <https://github.com/pCloud/pcloud-sdk-java>
- <https://pcloud.github.io/pcloud-sdk-java/>

Important implementation detail: pCloud has US and EU data regions. The official SDK handles API host selection; raw clients must preserve the location returned by OAuth.

## 3. Can an existing player be connected with almost no work?

Partly.

pCloud exposes WebDAV at:

- EU: `https://ewebdav.pcloud.com`
- US: `https://webdav.pcloud.com`

This enables immediate experiments with WebDAV-capable applications. The Crypto Folder is not available over WebDAV, and pCloud warns that WebDAV is not its most robust path for large transfers.

Relevant source:

- <https://help.pcloud.com/article/webdav>

Promising existing projects:

| Project | Strength | Limitation for this goal |
|---|---|---|
| auDAV | FOSS WebDAV audiobook streaming and progress | audiobook-specific; young project; password/WebDAV model |
| Symphony | FOSS Kotlin/Compose, folder views, queue, filename/path sorting | local MediaStore architecture; AGPL; remote source is invasive |
| Voice | mature FOSS audiobook progress, bookmarks, sleep timer, Android Auto | deliberately local-only; remote source is a large conceptual change |
| Voyager / Material Files | FOSS WebDAV file browsing | no integrated folder audio queue/progress player |
| Round Sync / RCX | rclone-backed pCloud/file access and streaming | integration is still split across apps; RCX itself is unmaintained |
| Audiobookshelf | excellent FOSS audiobook server/app | adds a server and library database, contrary to lowest-friction direct pCloud access |

References:

- <https://f-droid.org/packages/rocks.mm_dev.audav/>
- <https://github.com/zyrouge/symphony>
- <https://github.com/PaulWoitaschek/Voice>
- <https://github.com/zhanghai/MaterialFiles>
- <https://github.com/x0b/rcx>
- <https://github.com/advplyr/audiobookshelf>

## 4. Recommended strategy

Use a native pCloud adapter plus a source-neutral Media3 player.

Reasons:

1. It directly meets folder queue and player-to-folder navigation without fighting a tag-first schema.
2. The official SDK removes authentication, regional host, and protocol risk.
3. Media3 is the supported Android playback stack.
4. The small provider boundary leaves room for WebDAV, local SAF, S3, rclone, or another cloud source.
5. Independent Apache-2.0 source modules can be proposed upstream even when a candidate player uses GPL/AGPL.

WebDAV remains valuable as an immediate workaround and compatibility oracle, but should not be the only production transport.
