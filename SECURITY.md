# Security policy

Security fixes are applied to the latest `0.x` release line and `main`.

Use GitHub private vulnerability reporting. Do not open a public issue
containing access tokens, signed URLs, private filenames, account metadata, or
private media.

- pCloud OAuth tokens are encrypted locally and excluded from backup.
- Account passwords are never accepted by the native pCloud path.
- Signed links are resolved just in time and are not durable state.
- Public CI contains no provider credentials.
- Release signing credentials remain outside the repository and Docker layers.

Rotate or revoke any credential accidentally exposed before reporting it.
