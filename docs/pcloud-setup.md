# pCloud setup and live validation

properpcloud uses pCloud's official OAuth surface and Java/Android SDK. It does
not collect an account password.

## Create an application

1. Sign in to pCloud's developer console.
2. Create an application for personal/testing use.
3. Record the public client ID. A client ID identifies the application; it is
   not an account password or a client secret.
4. Install properpcloud and open **Settings → pCloud OAuth**.
5. Paste the client ID and choose **Connect pCloud**.
6. Complete authorization on pCloud's surface.

The returned access token is encrypted with an Android Keystore AES-GCM key and
excluded from app backup/device transfer. Disconnect removes the stored session.

## Regional API hosts

OAuth returns the account's API host. properpcloud rejects every host except:

```text
api.pcloud.com
eapi.pcloud.com
```

## Troubleshooting

### Connect button is disabled

Enter a non-empty client ID. Do not paste a client secret or account password.

### Authorization is cancelled or denied

No token is stored. Retry from Settings when ready.

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
