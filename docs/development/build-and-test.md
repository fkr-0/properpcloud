# Build and test

## Prerequisites

- Docker for the pinned Android and JDK 21 build image.
- Node.js 22 or newer for the documentation site.
- mpv and JDK 21 on the Linux host for the desktop smoke and interactive run targets.
- GitHub CLI only for release and repository-administration operations.

## Standard checks

| Command | Scope |
| --- | --- |
| `make doctor` | Tooling, SDK, wrapper, and repository preflight. |
| `make test` | Android, portable JVM, metadata, and source unit tests. |
| `make desktop-test` | Desktop adapter and persistence unit tests. |
| `make desktop-smoke` | Real host mpv IPC plus SQLite and generated media. |
| `make desktop-mpris-smoke` | Packaged runtime plus external MPRIS property calls on an isolated D-Bus. |
| `make lint` | Android lint and repository checks. |
| `make build` | Debug Android build and portable modules. |
| `make desktop-package` | Compose Desktop application image and native package inputs. |
| `make docs-build` | Markdown sync, type validation, and static site generation. |
| `make ci` | Complete local CI-equivalent sequence. |

## Gradle environment

The build uses JDK 21 toolchains and emits JVM 17-compatible bytecode for portable modules. Avoid running Gradle through an unwritable global cache; project helpers set `GRADLE_USER_HOME` to `.cache/gradle` or mount a writable cache in the build container.

## Unit-test boundaries

- Core tests use deterministic in-memory sources.
- Provider tests mock transport and SDK behavior; they do not require a real account.
- Desktop persistence tests use temporary SQLite databases.
- mpv command encoding is unit-tested without starting a player.
- `desktop-smoke` starts a real mpv process with `--no-config --ao=null`.

## Adding a source contract test

Every `AudioSource` implementation should pass the same behavioral suite:

```text
root has stable identity
list returns only direct children
load returns the requested stable node
resolveStream returns a playable transient capability
inspect never mutates provider state
folder IDs and file IDs cannot be confused
```

## Failure triage

1. Re-run the smallest affected task.
2. Inspect normalized diagnostics, not only the final Gradle exception.
3. Verify Docker cache ownership and available disk before changing dependencies.
4. Use the demo source to distinguish platform failures from provider failures.
5. Never paste credentials or signed links into an issue.
