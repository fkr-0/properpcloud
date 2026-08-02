# First run

## Verify the application before connecting an account

The application starts with the deterministic **Demo library**. It contains nested audiobook, field-recording, and numbered-track folders backed by generated WAV files.

1. Open **Audiobooks** and then **The Badger and the City**.
2. Play the folder.
3. Pause, seek, reorder the queue, and restart the application.
4. Confirm that the queue and resume position are restored.

This isolates local UI, storage, and playback problems from provider authentication or network issues.

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
