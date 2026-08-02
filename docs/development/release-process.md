# Release process

## Before tagging

1. Ensure `git status` contains only intended changes.
2. Update `VERSION`, `CHANGELOG.md`, specifications, and user-facing documentation.
3. Run the full local verification sequence.
4. Confirm the Pages site builds and the desktop smoke passes.
5. Review dependency and license changes.

```bash
make doctor
make ci
git diff --check
```

## Version semantics

properpcloud uses semantic versioning for public releases. The Android version code is derived from `major.minor.patch`; prerelease labels do not alter the integer mapping.

## Tag and release

```bash
git tag -s vX.Y.Z -m 'properpcloud X.Y.Z'
git push origin main
git push origin vX.Y.Z
```

The release workflow validates the tag against `VERSION`, builds the Android artifact, computes checksums, and publishes release notes and assets. Desktop release artifacts should be added only after their packaging task is reproducible in CI for the targeted distribution format.

## Pages deployment

The documentation workflow deploys on every push to `main` and can be dispatched manually. A failed documentation build must not be bypassed by committing generated HTML; fix the Markdown or renderer instead.

## Rollback

- Application release: publish a corrective release; do not rewrite an existing tag.
- Pages: revert the documentation or website commit and let the workflow deploy the previous state.
- DNS: restore the previous record from the recorded preflight state.
- GitHub rules: repository ruleset changes are independently auditable and should be reverted through the Rules API rather than force-pushing history.
