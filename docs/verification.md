# Verification evidence

Snapshot: 2026-08-01.

## Result

The current uncommitted bootstrap passes the complete local Docker-backed CI command:

```text
make ci
  → validate YAML specifications
  → run JVM tests
  → run Android lint
  → assemble debug APK

BUILD SUCCESSFUL in 3m 40s
181 actionable tasks: 174 executed, 7 from cache
```

The repository also passes `git diff --check`.

## Specification evidence

```yaml
documents_parsed: 10
requirements_validated: 16
use_cases_validated: 12
duplicate_mapping_keys: rejected
unknown_release_requirement_references: rejected
unknown_use_case_requirement_traces: rejected
untraced_must_requirements: rejected
```

## Test evidence

```yaml
test_suites: 2
tests: 4
failures: 0
errors: 0
skipped: 0
covered_bootstrap_logic:
  - natural filename ordering
  - disc and track ordering
  - pCloud folder-ID round trip
  - pCloud file-ID round trip
```

The larger test matrix under `spec/testing.yml` is a release contract, not a claim that all production features already exist.

## Lint evidence

Android lint succeeds with no error or warning-level findings.

Five informational findings remain:

```yaml
GradleDependency:
  count: 4
  reason: API level 37 is available for compilation.
OldTargetApi:
  count: 1
  reason: target SDK 36 is below API level 37.
```

This is intentional for the bootstrap. Android 16/API 36 is the current stable platform package and meets the Google Play requirement taking effect on 2026-08-31. Android 17/API 37 is still distributed as Beta 4.1 at this snapshot. API 37 belongs in the compatibility-test matrix before it becomes the production compile/target baseline.

References:

- <https://developer.android.com/tools/releases/platforms>
- <https://developer.android.com/about/versions/17/release-notes>
- <https://developer.android.com/google/play/requirements/target-sdk>

## Toolchain evidence

```yaml
java:
  distribution: Eclipse Temurin
  major: 17
  base_image_digest: sha256:b04a8c5d46e210873ffd1af6ad5f4d62c69ed3a6736993556eae60bba1373a23
gradle:
  version: 9.6.1
  wrapper_jar_sha256: 497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
android:
  command_line_tools: 15859902
  command_line_tools_sha256: 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
  compile_sdk: 36
  target_sdk: 36
  build_tools: 36.0.0
build:
  Android_Gradle_Plugin: 9.3.1
  Media3: 1.10.1
  pCloud_SDK: 1.11.0
  coroutines: 1.11.0
```

## Container evidence

```yaml
image_tag: properpcloud/android-build:2026.08
image_id: sha256:e1c27856056622503e78c716cc1653102cca9eef4de5e54d4ec0906f0f797b7a
image_size_bytes: 1136743686
contains:
  - JDK 17
  - Android command-line tools
  - platform tools
  - Android platform 36
  - build tools 36.0.0
omits:
  - emulator
  - NDK
  - CMake
  - second Gradle installation
```

## APK evidence

```yaml
path: app/build/outputs/apk/debug/app-debug.apk
size_bytes: 6165709
sha256: f2fe30b68f7fca838e1597f4384ac897c8681f2609d9c2a2fbdcc8522169ac99
package: dev.properpcloud.app
version_code: 1
version_name: 0.1.0-dev
minimum_sdk: 26
target_sdk: 36
compile_sdk: 36
```

The APK is a bootstrap shell, not a production-ready pCloud client. It proves the build, module boundaries, pCloud source adapter compilation, Media3 service integration, resources, manifest, and initial domain tests.

## Not yet verified

- Live pCloud OAuth and token restoration: requires a registered pCloud application and sandbox account.
- Live EU and US account-region behavior.
- Real folder browsing UI and folder-to-queue flow.
- Media3 playback against expiring pCloud links and range seeking.
- Room/DataStore migrations and process-death restoration.
- Offline cache and content verification.
- Metadata parsing and revision-safe remote writes.
- Android instrumentation, accessibility, performance, battery, and Android Auto suites.
- Linux client adapters and packaging.

These gaps are tracked as implementation phases and release gates rather than represented as completed features.
