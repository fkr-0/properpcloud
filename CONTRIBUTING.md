# Contributing

Thank you for improving properpcloud.

1. Read `AGENTS.md`, `spec/manifest.yml`, and the specification that owns the behavior.
2. Keep provider SDK types behind source-adapter boundaries.
3. Never commit credentials, client secrets, access tokens, signed links, private media, or account metadata.
4. Add or update a failing test before fixing observable behavior where practical.

```sh
make image
make doctor
make release-check
make ci
```

Describe the user problem, affected requirement, observable behavior, failure
semantics, tests, accessibility impact, and privacy impact. Add user-visible
changes under `Unreleased` in `CHANGELOG.md`. Do not move published tags.
