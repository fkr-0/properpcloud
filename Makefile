SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

IMAGE ?= properpcloud/android-build:2026.08
ANDROID_CMDLINE_TOOLS_VERSION ?= 15859902
ANDROID_CMDLINE_TOOLS_SHA256 ?= 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ANDROID_PLATFORM ?= 37.0
ANDROID_BUILD_TOOLS ?= 37.0.0
DESKTOP_JAVA_HOME ?= /opt/android-studio/jbr
PREBUILT_DESKTOP_IMAGE ?= 0
NPM ?= npm

DOTENV_PCLOUD_CLIENT_ID := $(shell python3 scripts/read-dotenv-public.py)
PCLOUD_CLIENT_ID ?= $(DOTENV_PCLOUD_CLIENT_ID)

export PROPERPCLOUD_BUILD_IMAGE := $(IMAGE)
export PCLOUD_CLIENT_ID

.PHONY: help oauth-config-check oauth-config-test toolchain-archive robolectric-runtime appimage-tool image image-no-cache doctor wrapper-check spec release-check release-client-id-check release-artifacts release-020-readiness release-020-pretag release-020-readiness-strict dependencies fast-test local-check test desktop-test desktop-smoke desktop-crash-recovery-smoke desktop-local-tag-recovery-process-smoke desktop-resilience-soak desktop-clean-profile-smoke desktop-mpris-smoke desktop-locked-keyring-smoke desktop-accessibility-audit desktop-sleep-monitor-smoke desktop-session-audit desktop-run desktop-package desktop-appimage desktop-appimage-smoke desktop-flatpak desktop-flatpak-smoke arch-package-gate linux-packages linux-package-smoke linux-ci docs-install docs-build lint build check ci shell compose install clean
.NOTPARALLEL: linux-ci linux-packages linux-package-smoke

help: ## Show available targets.
	@awk 'BEGIN {FS = ":.*## "; printf "properpcloud targets:\n\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

oauth-config-check: ## Validate public OAuth configuration without reading or exporting the client secret.
	@python3 scripts/read-dotenv-public.py --check
	@python3 scripts/validate-pcloud-client-id.py

oauth-config-test: ## Run host-side configuration and packaging boundary regression tests.
	@python3 -m unittest discover -s tests -p 'test_*.py'

toolchain-archive: ## Fetch and checksum-verify the resumable Android tools archive.
	@ANDROID_CMDLINE_TOOLS_VERSION=$(ANDROID_CMDLINE_TOOLS_VERSION) \
	  ANDROID_CMDLINE_TOOLS_SHA256=$(ANDROID_CMDLINE_TOOLS_SHA256) \
	  bash scripts/fetch-android-tools.sh

robolectric-runtime: ## Fetch and checksum-verify the Android 16 JVM test runtime.
	@bash scripts/fetch-robolectric-runtime.sh

appimage-tool: ## Fetch checksum-pinned appimagetool and its type-2 runtime.
	@bash scripts/fetch-appimagetool.sh

image: toolchain-archive ## Build the pinned Android SDK image with BuildKit.
	DOCKER_BUILDKIT=1 docker build \
	  --build-arg ANDROID_CMDLINE_TOOLS_VERSION=$(ANDROID_CMDLINE_TOOLS_VERSION) \
	  --build-arg ANDROID_CMDLINE_TOOLS_SHA256=$(ANDROID_CMDLINE_TOOLS_SHA256) \
	  --build-arg ANDROID_PLATFORM=$(ANDROID_PLATFORM) \
	  --build-arg ANDROID_BUILD_TOOLS=$(ANDROID_BUILD_TOOLS) \
	  --tag $(IMAGE) \
	  .

image-no-cache: toolchain-archive ## Rebuild the Android SDK image without Docker layer cache.
	DOCKER_BUILDKIT=1 docker build --no-cache \
	  --build-arg ANDROID_CMDLINE_TOOLS_VERSION=$(ANDROID_CMDLINE_TOOLS_VERSION) \
	  --build-arg ANDROID_CMDLINE_TOOLS_SHA256=$(ANDROID_CMDLINE_TOOLS_SHA256) \
	  --build-arg ANDROID_PLATFORM=$(ANDROID_PLATFORM) \
	  --build-arg ANDROID_BUILD_TOOLS=$(ANDROID_BUILD_TOOLS) \
	  --tag $(IMAGE) \
	  .

doctor: oauth-config-check wrapper-check ## Verify Docker, wrapper, OAuth configuration, and project prerequisites.
	@docker version >/dev/null
	@docker image inspect $(IMAGE) >/dev/null 2>&1 || { echo "Missing $(IMAGE); run 'make image'." >&2; exit 1; }
	@mkdir -p .cache/gradle
	@bash ./scripts/docker-run.sh --version

wrapper-check: ## Fail if the reviewed Gradle Wrapper is absent.
	@test -x ./gradlew || { echo "gradlew is missing or not executable" >&2; exit 1; }
	@test -f ./gradle/wrapper/gradle-wrapper.jar || { echo "gradle-wrapper.jar is missing" >&2; exit 1; }
	@test -f ./gradle/wrapper/gradle-wrapper.properties || { echo "gradle-wrapper.properties is missing" >&2; exit 1; }
	@test -f ./gradle/wrapper/gradle-wrapper.jar.sha256 || { echo "gradle-wrapper.jar.sha256 is missing" >&2; exit 1; }
	@cd gradle/wrapper && sha256sum --check --strict gradle-wrapper.jar.sha256

spec: oauth-config-test oauth-config-check ## Parse YAML and verify requirement/use-case traceability in Docker.
	@docker run --rm \
	  --user "$$(id -u):$$(id -g)" \
	  --entrypoint python3 \
	  --volume "$$PWD:/workspace:ro" \
	  --workdir /workspace \
	  $(IMAGE) \
	  scripts/validate-specs.py

release-check: spec ## Validate SemVer, changelog, license, and Android version wiring.
	@docker run --rm \
	  --user "$$(id -u):$$(id -g)" \
	  --entrypoint python3 \
	  --volume "$$PWD:/workspace:ro" \
	  --workdir /workspace \
	  $(IMAGE) \
	  scripts/validate-release.py

release-client-id-check: oauth-config-check ## Validate the public pCloud OAuth application ID when configured.

release-artifacts: oauth-config-check ## Prepare versioned APK, checksums, evidence, and release notes.
	@python3 scripts/prepare-release.py

release-020-readiness: oauth-config-test ## Validate and summarize explicit 0.2.0 promotion evidence without relaxing pending gates.
	@python3 scripts/validate-020-readiness.py

release-020-pretag: oauth-config-test ## Fail on every 0.2.0 blocker except an explicitly post-tag-only publication check.
	@python3 scripts/validate-020-readiness.py --pre-tag

release-020-readiness-strict: oauth-config-test ## Fail unless every 0.2.0 promotion and post-tag publication gate has passed.
	@python3 scripts/validate-020-readiness.py --strict

dependencies: ## Resolve dependencies without compiling production code.
	@bash ./scripts/docker-run.sh dependencies

fast-test: oauth-config-check ## Run portable JVM tests in the existing build image; never builds an APK.
	@bash ./scripts/docker-run.sh \
	  :core-model:test \
	  :metadata-online:test \
	  :metadata-tags:test \
	  :source-pcloud:test \
	  :source-webdav:test

local-check: oauth-config-test oauth-config-check ## Cheap host-side contract/config gate; compilation belongs to CI by default.

test: oauth-config-check robolectric-runtime ## Run JVM unit and module contract tests in Docker.
	@bash ./scripts/docker-run.sh test

desktop-test: ## Run the Linux desktop adapter and persistence tests in Docker.
	@bash ./scripts/docker-run.sh :desktop-app:test

desktop-smoke: desktop-package ## Verify generated media, SQLite, and a real host mpv JSON-IPC process.
	@command -v mpv >/dev/null || { echo "mpv is required" >&2; exit 1; }
	@test -x "$(DESKTOP_JAVA_HOME)/bin/java" || { echo "JDK 21 not found at $(DESKTOP_JAVA_HOME)" >&2; exit 1; }
	@mkdir -p .cache/gradle
	@JAVA_HOME="$(DESKTOP_JAVA_HOME)" GRADLE_USER_HOME="$$PWD/.cache/gradle" \
	  ./gradlew --no-daemon :desktop-app:run --args='--smoke'

desktop-crash-recovery-smoke: desktop-package ## Force mpv exit and verify explicit restart, stable identity, and bounded resume.
	@command -v mpv >/dev/null || { echo "mpv is required" >&2; exit 1; }
	@test -x "$(DESKTOP_JAVA_HOME)/bin/java" || { echo "JDK 21 not found at $(DESKTOP_JAVA_HOME)" >&2; exit 1; }
	@mkdir -p .cache/gradle
	@JAVA_HOME="$(DESKTOP_JAVA_HOME)" GRADLE_USER_HOME="$$PWD/.cache/gradle" \
	  ./gradlew --no-daemon :desktop-app:run --args='--crash-recovery-smoke'

desktop-local-tag-recovery-process-smoke: desktop-package ## SIGKILL after local tag replacement, restart packaged app, reselect root, and verify guarded rollback.
	@bash scripts/desktop-local-tag-recovery-process-smoke.sh \
	  desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud

desktop-resilience-soak: desktop-package ## Exercise pause/seek/checkpoint and controlled mpv recovery for a bounded retained run.
	@command -v mpv >/dev/null || { echo "mpv is required" >&2; exit 1; }
	@mkdir -p build/evidence
	@PROPERPCLOUD_SOAK_SECONDS="$${PROPERPCLOUD_SOAK_SECONDS:-120}" \
	  bash -o pipefail -c 'bash scripts/run-clean-profile.sh \
	    desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud --resilience-soak \
	    | tee build/evidence/0.2.0-resilience-soak.log'
	@python3 scripts/write-soak-evidence.py \
	  --log build/evidence/0.2.0-resilience-soak.log \
	  --output build/evidence/0.2.0-resilience-soak.json

desktop-clean-profile-smoke: desktop-package ## Run the packaged application smoke with isolated HOME and XDG state.
	@command -v mpv >/dev/null || { echo "mpv is required" >&2; exit 1; }
	@bash scripts/run-clean-profile.sh \
	  desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud --smoke

desktop-run: ## Launch the Compose Desktop client on the host.
	@test -x "$(DESKTOP_JAVA_HOME)/bin/java" || { echo "JDK 21 not found at $(DESKTOP_JAVA_HOME)" >&2; exit 1; }
	@mkdir -p .cache/gradle
	@JAVA_HOME="$(DESKTOP_JAVA_HOME)" GRADLE_USER_HOME="$$PWD/.cache/gradle" \
	  ./gradlew :desktop-app:run

desktop-package: ## Build the Linux Compose Desktop application image in Docker.
	@bash ./scripts/docker-run.sh :desktop-app:createDistributable

desktop-appimage: ## Build an x86_64 AppImage from the Compose Desktop image.
	@if [[ "$(PREBUILT_DESKTOP_IMAGE)" == "1" ]]; then \
	  test -x desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud || { \
	    echo "prebuilt desktop image is missing" >&2; exit 1; \
	  }; \
	else \
	  $(MAKE) desktop-package; \
	fi
	@bash scripts/package-appimage.sh

desktop-appimage-smoke: desktop-appimage ## Verify the built AppImage through private extraction and a clean profile.
	@image=$$(find build/releases -maxdepth 1 -type f -name 'properpcloud-*-x86_64.AppImage' -print -quit); \
	  test -n "$$image"; \
	  bash scripts/run-clean-profile.sh bash scripts/appimage-smoke.sh "$$image"

desktop-flatpak: ## Build an x86_64 single-file Flatpak bundle from the desktop image.
	@if [[ "$(PREBUILT_DESKTOP_IMAGE)" == "1" ]]; then \
	  test -x desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud || { \
	    echo "prebuilt desktop image is missing" >&2; exit 1; \
	  }; \
	else \
	  $(MAKE) desktop-package; \
	fi
	@bash scripts/package-flatpak.sh

desktop-flatpak-smoke: desktop-flatpak ## Temporarily install and verify the built Flatpak bundle.
	@bundle=$$(find build/releases -maxdepth 1 -type f -name 'properpcloud-*-x86_64.flatpak' -print -quit); \
	  test -n "$$bundle"; \
	  bash scripts/flatpak-bundle-smoke.sh "$$bundle"

linux-packages: desktop-appimage desktop-flatpak ## Build AppImage and Flatpak release packages.

linux-package-smoke: desktop-appimage-smoke desktop-flatpak-smoke ## Build and verify both Linux release packages.

desktop-mpris-smoke: desktop-package ## Verify packaged MPRIS properties over an isolated session bus.
	@command -v dbus-run-session >/dev/null || { echo "dbus-run-session is required" >&2; exit 1; }
	@command -v gdbus >/dev/null || { echo "gdbus is required" >&2; exit 1; }
	@dbus-run-session -- bash scripts/desktop-mpris-smoke.sh

desktop-locked-keyring-smoke: desktop-package ## Verify bounded behavior against an isolated, deliberately locked Secret Service collection.
	@bash scripts/desktop-locked-keyring-smoke.sh \
	  desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud

desktop-accessibility-audit: desktop-package ## Retain 200 percent high-contrast base and keyboard-help captures under Xvfb.
	@bash scripts/desktop-accessibility-audit.sh \
	  desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud

desktop-sleep-monitor-smoke: desktop-package ## Verify the packaged logind PrepareForSleep system-bus subscription.
	@desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud --sleep-monitor-smoke

desktop-session-audit: desktop-package ## Record redacted Secret Service and externally driven MPRIS evidence from the current desktop session.
	@python3 scripts/desktop-session-audit.py \
	  --app desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud

arch-package-gate: ## Clean-build, install, and smoke the immutable current-version Arch package in a container.
	@bash scripts/arch-package-gate.sh

linux-ci: desktop-test desktop-package desktop-smoke desktop-crash-recovery-smoke desktop-local-tag-recovery-process-smoke desktop-clean-profile-smoke desktop-mpris-smoke desktop-locked-keyring-smoke desktop-accessibility-audit ## Run native Linux unit, package, recovery, keyring, accessibility, mpv, SQLite, and MPRIS gates.

docs-install: ## Install the pinned documentation renderer dependencies.
	@cd website && $(NPM) ci

docs-build: docs-install ## Validate Markdown content and build static GitHub Pages output.
	@cd website && ASTRO_TELEMETRY_DISABLED=1 $(NPM) run build

lint: oauth-config-check ## Run Android lint in Docker.
	@bash ./scripts/docker-run.sh lint

build: oauth-config-check ## Build the debug APK in Docker.
	@bash ./scripts/docker-run.sh :app:assembleDebug

check: oauth-config-test oauth-config-check robolectric-runtime ## Run tests, lint, and debug assembly in one Gradle invocation.
	@bash ./scripts/docker-run.sh test lint :app:assembleDebug :desktop-app:createDistributable

ci: oauth-config-test oauth-config-check release-check robolectric-runtime docs-build ## Execute the complete repository verification set.
	@bash ./scripts/docker-run.sh \
	  --configuration-cache \
	  --build-cache \
	  test \
	  lint \
	  :app:assembleDebug \
	  :desktop-app:createDistributable

shell: ## Open an interactive shell in the Android SDK image.
	@mkdir -p .cache/gradle
	@docker run --rm -it \
	  --user "$$(id -u):$$(id -g)" \
	  --entrypoint /bin/bash \
	  --env HOME=/tmp/properpcloud-home \
	  --env GRADLE_USER_HOME=/gradle-cache \
	  --env PCLOUD_CLIENT_ID="$(PCLOUD_CLIENT_ID)" \
	  --volume "$$PWD:/workspace" \
	  --volume "$$PWD/.cache/gradle:/gradle-cache" \
	  --workdir /workspace \
	  $(IMAGE)

compose: oauth-config-check ## Run the default one-shot Compose build.
	@LOCAL_UID=$$(id -u) LOCAL_GID=$$(id -g) docker compose run --rm android-build

install: build ## Install the debug APK using host adb.
	adb install -r app/build/outputs/apk/debug/app-debug.apk

clean: ## Delete Gradle outputs through the container.
	@bash ./scripts/docker-run.sh clean
