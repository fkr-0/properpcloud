# Verification evidence

Current release candidate: `0.1.0` on 2026-08-02.

Machine-readable release evidence is stored in:

- `docs/releases/0.0.1.yml`
- `docs/releases/0.1.0.yml`

## Complete release gate

```text
make ci

spec: validated 10 documents, 17 requirements, and 13 use cases
release: version=0.1.0 tag=v0.1.0 metadata=ok
BUILD SUCCESSFUL in 39s
192 actionable tasks
```

The same tree passes `git diff --check`.

## Tests

```yaml
suites: 8
tests: 21
failures: 0
errors: 0
skipped: 0
```

Covered behavior includes:

- natural filename and disc/track sorting;
- pCloud file/folder identity and accepted regional hosts;
- atomic queue replacement, play-next, append, reorder, removal, and selection;
- recursive folder traversal, omissions, and previous-queue preservation;
- progress completion and smart rewind;
- deterministic demo folders and generated valid WAV media;
- DataStore queue/progress round trips without signed-link persistence;
- compact Compose navigation, library rendering, and OAuth settings controls.

## Lint

Android lint completes with:

```yaml
errors: 0
warnings: 0
informational:
  NewerVersionAvailable: 1
  OldTargetApi: 1
```

The informational target notice is intentional: current AndroidX is compiled
against API 37 while runtime behavior remains targeted to stable API 36 until
the Android 17 compatibility matrix is completed.

## Toolchain

```yaml
Java: Eclipse Temurin 21
Gradle: 9.6.1
Android_Gradle_Plugin: 9.3.1
compile_sdk: 37
target_sdk: 36
build_tools: 37.0.0
toolchain_image_id: sha256:93420ccd18a67c7e3a72868111b7dcc24cd51e5dafb0a8003b41b10fe0d7d43a
Gradle_wrapper_sha256: 497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
Android_tools_sha256: 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
Robolectric_runtime_sha256: 16f1f751643d1d3d5592008846bbdfc1e57cff15e6ec303d26584de3b6ac25ec
```

## APK

```yaml
path: app/build/outputs/apk/debug/app-debug.apk
package: dev.properpcloud.app
version_code: 1000
version_name: 0.1.0
minimum_sdk: 26
compile_sdk: 37
target_sdk: 36
size_bytes: 23822451
sha256: c24832c85b9e0c57f7579a0ed4a46e28ee5e403b8a3bf6b54dddd66ba1440558
signing: Android debug key
```

## Honest external gates

The deterministic demo source is fully validated in public CI. The following
cannot be inferred without maintainer-provided devices/accounts and remain
explicit external gates:

- live pCloud OAuth for US and EU accounts;
- large private folder traversal and provider-specific codecs;
- expired-link renewal against real pCloud capabilities;
- account revocation and restoration;
- physical-device TalkBack/large-font review;
- Android 17 runtime compatibility;
- production signing and store distribution.

The checklist is in `docs/pcloud-setup.md`. Release language must preserve this
distinction rather than claiming live-provider validation from fakes.
