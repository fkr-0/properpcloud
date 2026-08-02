SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

IMAGE ?= properpcloud/android-build:2026.08
ANDROID_CMDLINE_TOOLS_VERSION ?= 15859902
ANDROID_CMDLINE_TOOLS_SHA256 ?= 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ANDROID_PLATFORM ?= 37.0
ANDROID_BUILD_TOOLS ?= 37.0.0

export PROPERPCLOUD_BUILD_IMAGE := $(IMAGE)

.PHONY: help toolchain-archive robolectric-runtime image image-no-cache doctor wrapper-check spec release-check release-artifacts dependencies test lint build check ci shell compose install clean

help: ## Show available targets.
	@awk 'BEGIN {FS = ":.*## "; printf "properpcloud targets:\n\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

toolchain-archive: ## Fetch and checksum-verify the resumable Android tools archive.
	@ANDROID_CMDLINE_TOOLS_VERSION=$(ANDROID_CMDLINE_TOOLS_VERSION) \
	  ANDROID_CMDLINE_TOOLS_SHA256=$(ANDROID_CMDLINE_TOOLS_SHA256) \
	  bash scripts/fetch-android-tools.sh

robolectric-runtime: ## Fetch and checksum-verify the Android 16 JVM test runtime.
	@bash scripts/fetch-robolectric-runtime.sh

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

doctor: wrapper-check ## Verify Docker, wrapper, and project prerequisites.
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

spec: ## Parse YAML and verify requirement/use-case traceability in Docker.
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

release-artifacts: ## Prepare versioned APK, checksums, evidence, and release notes.
	@python3 scripts/prepare-release.py

dependencies: ## Resolve dependencies without compiling production code.
	@bash ./scripts/docker-run.sh dependencies

test: robolectric-runtime ## Run JVM unit and module contract tests in Docker.
	@bash ./scripts/docker-run.sh test

lint: ## Run Android lint in Docker.
	@bash ./scripts/docker-run.sh lint

build: ## Build the debug APK in Docker.
	@bash ./scripts/docker-run.sh :app:assembleDebug

check: robolectric-runtime ## Run tests, lint, and debug assembly in one Gradle invocation.
	@bash ./scripts/docker-run.sh test lint :app:assembleDebug

ci: release-check robolectric-runtime ## Execute the complete hermetic CI verification set.
	@bash ./scripts/docker-run.sh \
	  --configuration-cache \
	  --build-cache \
	  test \
	  lint \
	  :app:assembleDebug

shell: ## Open an interactive shell in the Android SDK image.
	@mkdir -p .cache/gradle
	@docker run --rm -it \
	  --user "$$(id -u):$$(id -g)" \
	  --entrypoint /bin/bash \
	  --env HOME=/tmp/properpcloud-home \
	  --env GRADLE_USER_HOME=/gradle-cache \
	  --volume "$$PWD:/workspace" \
	  --volume "$$PWD/.cache/gradle:/gradle-cache" \
	  --workdir /workspace \
	  $(IMAGE)

compose: ## Run the default one-shot Compose build.
	@LOCAL_UID=$$(id -u) LOCAL_GID=$$(id -g) docker compose run --rm android-build

install: build ## Install the debug APK using host adb.
	adb install -r app/build/outputs/apk/debug/app-debug.apk

clean: ## Delete Gradle outputs through the container.
	@bash ./scripts/docker-run.sh clean
