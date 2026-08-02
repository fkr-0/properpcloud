# Accounts and security

## Credential storage

| Platform | Stored material | Storage |
| --- | --- | --- |
| Android | Provider session token and host metadata | Android encrypted application storage |
| Linux | Serialized provider session token and regional API host | freedesktop Secret Service via `secret-tool` |

The account password is not persisted by properpcloud.

## Current pCloud sign-in flow

The current cross-platform implementation provides a direct pCloud sign-in flow:

1. The user selects a regional API host.
2. The client sends email and password once over HTTPS to pCloud's user-info authentication endpoint.
3. pCloud returns a bounded legacy authentication token and user identity.
4. The password buffer is overwritten.
5. The token is stored in the platform credential vault.

This flow is explicitly an interim compatibility path. Browser OAuth remains the preferred production direction once the application's registered redirect configuration is finalized with pCloud.

## What properpcloud does not store

- account passwords;
- pCloud client secrets;
- signed download URLs;
- provider media bytes in application logs;
- credentials in command-line arguments;
- credentials in SQLite queue or progress tables.

## Disconnecting

Disconnect removes the locally stored session and switches the current source to the demo library. Provider-side token revocation is a distinct operation; consult the account security page when a device has been lost or compromised.

## Linux Secret Service diagnostics

```bash
secret-tool lookup service properpcloud key pcloud-session >/dev/null
echo $?
```

Do not print the returned value. Exit status zero confirms a matching secret exists. To remove it manually:

```bash
secret-tool clear service properpcloud key pcloud-session
```

## Support bundles

Before sharing logs, verify that they contain no account identifiers or provider responses. The desktop playback adapter discards mpv standard output and error by design because the command line may transiently contain a signed URL.
