# properpcloud agent contract

## Product invariant

The filesystem is a first-class library model. Never make embedded tags the only path to browsing, ordering, queue construction, or navigation.

## Build invariant

- Build and test through the Docker-backed Make targets.
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

1. `make doctor`
2. `make test`
3. `make lint`
4. `make build`
5. `make ci` before release-oriented changes

## Safety

- Remote metadata edits are opt-in, previewed, hash/revision guarded, and verified after upload.
- Logout invalidates local credentials immediately; remote token revocation is attempted and surfaced separately.
- Logs and diagnostics redact bearer tokens, passwords, signed URLs, and private path segments where configured.
