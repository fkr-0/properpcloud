# Security policy

Security fixes are applied to the latest `0.x` release line and `main`.

Use GitHub private vulnerability reporting. Do not open a public issue
containing access tokens, signed URLs, private filenames, account metadata, or
private media.

- pCloud OAuth and documented direct-login `auth` tokens are encrypted locally and excluded from backup.
- OAuth is preferred and keeps the password on pCloud's page. The clearly labelled interim direct-login path accepts a password only for one regional HTTPS request; it never persists, logs, exports, backs up, or restores that password.
- Direct login never probes both account regions, follows redirects, or places credentials in URLs.
- Signed links are resolved just in time and are not durable state.
- Public CI contains no provider credentials.
- Release signing credentials remain outside the repository and Docker layers.

Rotate or revoke any credential accidentally exposed before reporting it.
