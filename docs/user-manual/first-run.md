# First run

## Choose a library

On Android, first run shows **No library connected**. Connect pCloud directly or configure the optional server catalog before browsing; the production Android app no longer contains a fabricated demo library.

On Linux, the deterministic generated-WAV source remains available for credential-free host verification. It can still be used to isolate local UI, storage, mpv, and persistence problems from provider authentication or network issues.

## Connect pCloud

1. Open the account action in the top bar or settings screen.
2. Select **Europe** for accounts hosted at `eapi.pcloud.com`, otherwise **United States** for `api.pcloud.com`.
3. Enter the account email and password.
4. Wait for the root folder to load.

The password is used for one provider HTTPS request and is cleared from the mutable request buffer. The returned session token is stored in Android encrypted storage or Linux Secret Service. See [Accounts and security](accounts-and-security.md) for the exact trust boundary.

## Confirm the account region

Selecting the wrong region commonly produces a provider rejection even when the credentials are correct. The application never silently retries credentials against both regions.

## Recommended initial settings

- Sorting: **disc and track, then natural filename**.
- Folder playback: direct children for albums; recursive for audiobook or podcast trees.
- Resume: keep the default smart rewind so speech resumes with context.
- Metadata: inspect first; do not enable write operations until the proposed backup and rollback path is understood.
