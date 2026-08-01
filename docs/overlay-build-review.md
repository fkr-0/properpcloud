# Overlay Cam build review and adaptation

`/home/user/code/overlay-cam` provides a good operational pattern for a small Android project:

```text
edit source on host
      ↓
compile and test in Docker
      ↓
write APK into mounted project
      ↓
install and inspect through host adb
```

That pattern is retained. The implementation is modernized and tightened for `properpcloud`.

## Strengths retained

### One build front door

Overlay Cam exposes Docker, build, test, shell, install, and CI behavior through `make`. This keeps long Docker commands out of normal development and gives agents and humans the same vocabulary.

`properpcloud` retains and expands this:

```text
make image
make doctor
make test
make lint
make build
make ci
make shell
make install
```

### Host source, container toolchain

The source remains directly editable with native tools, while Java, Android SDK, build tools, and Gradle execution are isolated. This is materially simpler than developing inside a persistent container and avoids copying source into and out of an image.

### Host ADB boundary

The container produces the APK; the host owns USB authorization, device visibility, installation, and logcat. Passing USB and ADB server state into the build container would add privileges and platform-specific failure modes without improving build reproducibility.

### Docker and CI parity

Overlay Cam's CI builds the same image used locally. `properpcloud` keeps this and adds BuildKit caching so the parity does not require repeating every SDK and dependency download.

### Interactive diagnosis

A development shell remains available, but it is not the primary build path. Normal commands remain non-interactive and auditable.

## Weaknesses corrected

### Unverified Android tools download

Overlay Cam downloads a command-line-tools archive without verifying its digest. A compromised mirror, proxy, or accidental archive replacement would enter the build image unnoticed.

`properpcloud` pins the current archive identifier and verifies SHA-256 before extraction. The archive is fetched into an ignored project cache with resumable segmented downloads, so a transport failure does not restart the large download from zero.

### Hidden SDK installation failure

Overlay Cam ends `sdkmanager` installation with `|| true`. This can cache an image missing a platform, build tools, NDK, or CMake and defer the actual failure to a later build.

`properpcloud` fails the image build immediately. A reproducible toolchain cannot treat missing SDK components as optional accidents.

### Unnecessary NDK and CMake

Overlay Cam installs CMake and the NDK despite being a Kotlin/Compose application. These packages substantially increase image size and download time.

`properpcloud` installs only platform tools, platform 36, and build tools 36.0.0. Native components are added only when a reviewed module needs them.

### Two Gradle installations

Overlay Cam installs Gradle 8.9 globally and also uses a Gradle Wrapper. The global Gradle exists mainly to generate a wrapper if it is missing. This creates version drift and makes a missing reviewed wrapper look normal.

`properpcloud` uses one launcher: the committed Gradle Wrapper. The image contains a JDK and Android SDK, not a second Gradle distribution.

### Build-time source mutation

Overlay Cam's entry script generates `gradlew` when absent. That is convenient for an initial prototype but wrong for a controlled build:

- a build changes the source checkout;
- the generated wrapper version may not match reviewed project intent;
- wrapper JAR and properties are not part of the original commit;
- CI can pass despite an incomplete repository.

`properpcloud` fails with a clear diagnostic when wrapper files are absent. Wrapper generation is an explicit reviewed repository operation.

### Global host Gradle cache

Overlay Cam mounts `$HOME/.gradle` into `/.gradle`. This may mix unrelated projects, host ownership, credentials, init scripts, and state into the container.

`properpcloud` uses a project-local ignored `.cache/gradle`, runs as the host UID/GID, and assigns a temporary container home. This is less magical and easier to clean or reproduce.

### Root-owned output risk

A default Docker container runs as root and can write root-owned build artifacts into the mounted checkout. `properpcloud` passes the host UID/GID for normal runs.

### Version drift across files

Overlay Cam has versions distributed between its Dockerfile, settings, app build file, scripts, and comments. `properpcloud` centralizes library and plugin versions in `gradle/libs.versions.toml` and SDK image versions in explicit Make/Docker arguments.

### Legacy Compose schema

Overlay Cam declares the obsolete Compose file `version` key. `properpcloud` uses the current Compose Specification directly.

### Limited Docker layer reuse in CI

Overlay Cam runs a plain image build on every CI job. `properpcloud` uses Buildx with GitHub Actions cache for the toolchain image and separately caches project Gradle data.

### Configuration-cache posture

Overlay Cam enables configure-on-demand, an older optimization with weaker relevance for a modern Gradle build. `properpcloud` enables configuration cache in warning mode during bootstrap, build cache, and parallelism. It should move configuration-cache incompatibilities to hard failure after all CI tasks are compatible.

## Resulting build topology

```text
Dockerfile
├── JDK 17 base
├── checksum-verified Android command-line tools
├── platform-tools
├── platform 36
└── build-tools 36.0.0

committed source
├── Gradle Wrapper 9.6.1
├── AGP 9.3.0 with built-in Kotlin
├── version catalog
└── Android modules

Makefile / docker-run.sh
├── bind source as /workspace
├── bind .cache/gradle as /gradle-cache
├── use host UID:GID
└── invoke wrapper through immutable image entrypoint
```

## Remaining hardening steps

The bootstrap intentionally leaves several release-grade improvements explicit rather than pretending they are already complete:

1. pin the JDK base image by digest for release branches;
2. commit Gradle dependency verification metadata;
3. add dependency locking where it is practical and useful;
4. generate an SBOM;
5. scan the image and dependencies;
6. verify unsigned build reproducibility and record known nondeterminism;
7. isolate release signing from the general build image;
8. pin GitHub Actions to commit hashes if the repository adopts that policy.

These steps are included in `spec/build.yml` and the release Definition of Done.
