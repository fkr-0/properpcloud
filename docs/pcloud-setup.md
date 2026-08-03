# pCloud setup and live validation

properpcloud supports two documented pCloud authentication paths. OAuth is the
preferred long-term path because account credentials stay on pCloud's own page.
An explicitly labelled direct-login fallback remains available for unconfigured
source builds and account/provider cases where OAuth cannot be completed.

## Preferred: OAuth

A published build may contain properpcloud's public pCloud application client ID.
Open **Settings → pCloud account** and choose **Continue with pCloud OAuth**.
Authentication and consent happen on pCloud's authorization page. The approved
OAuth token returns directly to properpcloud and is encrypted locally; users do
not copy tokens or enter their pCloud password into properpcloud.

A client ID identifies the application. It is not tied to the user's account,
cannot be derived from that account, is not a password, and is not a client
secret.

### Maintainer OAuth setup

The properpcloud application has been created and its client credentials are
available to maintainers. Client builds consume only the public client ID. The
client secret is intentionally not read, exported to Docker, written to
`BuildConfig`, or included in release artifacts.

1. Sign in to pCloud's developer console and verify the properpcloud application.
2. Confirm that the application permits the Android SDK token flow.
3. Register `pcloud-oauth://dev.properpcloud.app` as the redirect URI.
4. Enable **Allow implicit grant**, as required by pCloud's Android SDK token flow.
5. Put only the public client ID in the ignored local `.env` as
   `PCLOUD_CLIENT_ID=...` and run `make oauth-config-check`.
6. Record the same public client ID as the GitHub repository variable
   `PCLOUD_CLIENT_ID`. Never configure the client secret as a client-build or
   release-workflow variable.
7. Rebuild a tagged release and complete the protected EU/US OAuth and logout gate.

Local Make and Docker Compose builds automatically read only `PCLOUD_CLIENT_ID`
from `.env`. They never source the file and never pass `PCLOUD_CLIENT_SECRET` into
the build container. The entire `.env*` family is excluded from Docker build
contexts, and only `.env.example` may be committed.

For a personal build, copy the template and set the public ID:

```sh
cp .env.example .env
# edit PCLOUD_CLIENT_ID in .env
make oauth-config-check
make build
```

An explicit environment variable or Gradle property still overrides `.env` for CI
and controlled builds.

## Fallback: direct pCloud account sign-in

pCloud's HTTP JSON authentication documentation permits an HTTPS request with
`username`, `password`, `getauth=1`, `logout=1`, and a device name. A successful
`userinfo` response contains an `auth` token. This path requires no application
client ID.

Open **Settings → pCloud account → Fallback direct sign-in**, then:

1. choose the account's storage region—**Europe** or **United States**;
2. enter the pCloud account email and password;
3. choose **Sign in directly**.

The implementation deliberately:

- sends credentials only to `https://eapi.pcloud.com/userinfo` or
  `https://api.pcloud.com/userinfo`, according to the user's explicit choice;
- uses an HTTPS form POST, not URL query parameters;
- disables redirects, applies 15-second timeouts, and bounds the response body;
- clears the password field before starting the request;
- never stores, logs, backs up, exports, or includes the password in diagnostics;
- requests a 90-day absolute token lifetime and 30-day inactivity lifetime;
- encrypts only the returned `auth` token with Android Keystore AES-GCM;
- marks the session as a legacy-auth-token session so subsequent SDK requests send
  `auth` in an HTTPS POST body rather than an OAuth bearer header or URL;
- invalidates that token through the matching regional `logout` method on disconnect.

The password necessarily exists transiently in Android UI/process memory while the
user types and the request is encoded. Java/Kotlin strings cannot be reliably
zeroized, so properpcloud does not claim impossible memory erasure; it minimizes
lifetime, clears mutable buffers, and never persists the value.

This fallback may be rejected for accounts or provider policies requiring a
separate two-factor challenge. pCloud's public direct-login documentation does not
describe that challenge. Use OAuth when available for such accounts.

## Session storage and disconnect

Both OAuth and direct-login tokens are encrypted with an Android Keystore AES-GCM
key and excluded from backup/device transfer. The stored session also records its
token kind and regional host so the correct SDK authentication and logout protocol
is restored after process restart.

Disconnect removes the local encrypted session and provider source first. It then
attempts provider-side invalidation:

- OAuth token: regional `/logout` with HTTPS `Authorization: Bearer`;
- direct-login token: regional `/logout` with an HTTPS form-body `auth` parameter.

Local removal and remote confirmation remain separate outcomes.

## Regional API hosts

properpcloud rejects every account API host except:

```text
api.pcloud.com   # United States
eapi.pcloud.com  # Europe
```

The direct flow never probes both regions with a password. The user must choose the
region explicitly.

## Troubleshooting

### OAuth button is unavailable

The build has no bundled application identity and no custom override. Confirm that
the repository-root `.env` contains `PCLOUD_CLIENT_ID`, run
`make oauth-config-check`, or enter a public client ID under
**OAuth developer setup**. Never enter a client secret or access token there.

### Direct sign-in is rejected

- verify the email and password;
- verify Europe versus United States;
- use OAuth when the account requires two-factor authentication;
- note the numeric provider code shown by the app, but never include credentials in
  a report.

### Disconnect says remote invalidation was unconfirmed

The encrypted local session has already been removed, so the app cannot make
further authenticated requests. A network/provider failure prevented confirmation.
Use pCloud account-security/token controls when independent assurance is required.

### Folder cannot be loaded

- confirm the device has network access;
- disconnect/reconnect if authorization was revoked;
- confirm the account's regional API is reachable;
- share only synthetic/non-sensitive screenshots and inspection data in bug reports.

### Playback link expires

Stream links are capabilities and are never durable state. Media3 resolves a fresh
link before playback and performs one bounded refresh when an eligible HTTP 401/403
response indicates expiry. Repeated failure is surfaced rather than looped.

## Protected live-account validation checklist

Public CI contains no provider credentials. A maintainer should use a disposable
account and, for OAuth, a disposable application registration.

```yaml
authentication:
  oauth:
    - grant US account
    - grant EU account
    - deny and cancel
    - wrong callback/nonce
  direct:
    - successful EU account
    - successful US account
    - wrong password
    - wrong region
    - account with two-factor authentication
    - verify password absent from logcat, backup, state restoration, and reports
  disconnect:
    - OAuth token invalidated
    - direct auth token invalidated
    - offline local-first removal
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
  - restore queue, progress, token kind, and regional host
privacy:
  - no password or token in logcat
  - no signed link in persisted preferences
  - no credentials in reports, backups, or release artifacts
```

Record evidence privately with synthetic/redacted names. Public release notes must
state whether the live gate was completed; deterministic fixtures are not a
substitute for real account validation.
