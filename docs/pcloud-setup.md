# pCloud setup and live validation

properpcloud uses pCloud's official OAuth surface and Java/Android SDK. It does
not collect an account password.

## Normal user sign-in

A published properpcloud build contains the project's public pCloud application
client ID. Open **Settings → pCloud account** and choose **Sign in to pCloud**.
Authentication and consent happen on pCloud's official authorization page. The
approved token returns directly to properpcloud and is encrypted locally; users
do not copy or paste access tokens.

A client ID identifies the application. It is not tied to the user's account,
cannot be discovered from that account, is not a password, and is not a client
secret.

## Maintainer release setup

1. Sign in to pCloud's developer console.
2. Create the properpcloud application.
3. Register the redirect URI `pcloud-oauth://dev.properpcloud.app`.
4. Enable **Allow implicit grant**, as required by pCloud's Android SDK token flow.
5. Record the public client ID as the GitHub repository variable
   `PCLOUD_CLIENT_ID`. Do not store or embed the client secret.
6. Build the tagged release. The release workflow fails before building when the
   variable is absent or blank.

For a personal or local test build, open **Advanced setup** in the app and paste
your own public client ID, or build with:

```sh
PCLOUD_CLIENT_ID=your_public_client_id make build
```

The returned access token is encrypted with an Android Keystore AES-GCM key and
excluded from app backup/device transfer. Disconnect removes the stored session
immediately and then attempts pCloud's token-invalidating `logout` method.

## Regional API hosts

OAuth returns the account's API host. properpcloud rejects every host except:

```text
api.pcloud.com
eapi.pcloud.com
```

## Troubleshooting

### Sign-in button is disabled

The build has no bundled application identity and no custom override. Published
releases must be rebuilt with `PCLOUD_CLIENT_ID`; local testers may enter a public
client ID under **Advanced setup**. Never paste a client secret, password, or token.

### Authorization is cancelled or denied

No token is stored. Retry from Settings when ready.

### Disconnect says remote invalidation was unconfirmed

The encrypted local session has already been removed, so the app cannot make
further authenticated requests. A network/provider failure prevented confirmation
from pCloud. Revoke properpcloud from pCloud's account security/application settings
when assurance is required.

### Folder cannot be loaded

- confirm the device has network access;
- disconnect/reconnect if authorization was revoked;
- confirm the account's regional API is reachable;
- use **Inspect metadata** only with synthetic/non-sensitive screenshots when
  reporting a bug.

### Playback link expires

Stream links are capabilities and are never durable state. Media3 resolves a
fresh link before playback and performs one bounded refresh when an eligible
HTTP 401/403 response indicates expiry. Repeated failure is surfaced rather than
looped indefinitely.

## Protected live-account validation checklist

Public CI cannot run this checklist because it contains no provider credentials.
A maintainer should use a disposable application and test account.

```yaml
authorization:
  - grant US account
  - grant EU account
  - deny
  - cancel
  - revoke then retry
  - disconnect and verify local removal
folders:
  - root and three nested levels
  - empty folder
  - mixed audio/non-audio folder
  - folder above 500 entries
queue:
  - direct folder
  - recursive subtree
  - cancel traversal
  - unreadable child produces partial report
playback:
  - MP3
  - M4A or M4B
  - FLAC
  - Ogg or Opus
  - WAV
  - range seek
  - expired-link renewal at same position
resilience:
  - kill process while browsing
  - kill process while scanning
  - kill process during playback
  - restore queue and progress
privacy:
  - no token in logcat
  - no signed link in persisted preferences
  - no credentials in reports, backups, or release artifacts
```

Record evidence in a private maintainer report with synthetic/redacted names.
The public release notes should say whether this gate was completed; never infer
live validation from deterministic fixtures.
