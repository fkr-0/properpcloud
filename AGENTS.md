# properpcloud agent contract

## Product invariant

The filesystem is a first-class library model. Never make embedded tags the only path to browsing, ordering, queue construction, or navigation.

## Build invariant

- Build and JVM/Android tests through the Docker-backed Make targets. Host-only specification
  and configuration checks may run through their Make targets without Docker.
- The committed Gradle Wrapper is mandatory; container entrypoints must not generate or mutate it.
- Never commit OAuth tokens, passwords, signing keys, downloaded media, Gradle caches, Android SDK contents, APKs, or AABs.
- Keep Android SDK and Gradle versions pinned and checksum-verified where the upstream provides checksums.
- Do not hide failed `sdkmanager`, Gradle, lint, or test commands with `|| true`.

## Architecture invariant

- Domain and application logic must not import Android, Media3, Compose, pCloud SDK, Room, or transport classes.
- Provider modules implement source capability interfaces; provider IDs and direct URLs must not leak into generic business rules.
- Persist stable source/node identities. Never persist expiring stream URLs as media identity.
- Every state-changing use case exposes an explicit command/result boundary and is testable with in-memory fakes.
- Android and Linux clients share domain, application, provider, queue, metadata, and policy logic wherever JVM portability permits.

## Specification invariant

The normative product and architecture contracts are under `spec/`. When code changes a contract, update the corresponding YAML and tests in the same change.

## Verification order

Routine development should avoid repeatedly compiling the Android application. Use the
narrowest portable check that covers the change and let the push/pull-request GitHub Actions
workflow own Robolectric, Android lint, APK assembly, documentation build, and the complete
`make ci` gate.

1. `make local-check` for ordinary shared/domain/provider changes.
2. A narrower Docker-backed Gradle module test is useful when the pinned image is already
   present; do not build or pull the Android toolchain only to duplicate CI.
3. Do not run `make lint`, `make build`, or `make ci` locally unless the user explicitly asks,
   CI is unavailable, or the work is an intentional release/debug verification pass.
4. Release-oriented local verification, when explicitly required, remains `make doctor`,
   `make test`, `make lint`, `make build`, then `make ci`.

## Safety

- Remote metadata edits are opt-in, previewed, hash/revision guarded, and verified after upload.
- Logout invalidates local credentials immediately; remote token revocation is attempted and surfaced separately.
- Logs and diagnostics redact bearer tokens, passwords, signed URLs, and private path segments where configured.
