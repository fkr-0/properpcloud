# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21@sha256:b9142586f9712700c6c9e07adcedfb18608b1a3a056e4001423a3354adfa9d80

ARG ANDROID_CMDLINE_TOOLS_VERSION=15859902
ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_PLATFORM=37.0
ARG ANDROID_BUILD_TOOLS=37.0.0

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache \
    HOME=/tmp/properpcloud-home \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        python3 \
        python3-yaml \
        unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .cache/toolchain/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip /tmp/android-command-line-tools.zip

RUN set -eux; \
    archive="/tmp/android-command-line-tools.zip"; \
    echo "${ANDROID_CMDLINE_TOOLS_SHA256}  ${archive}" | sha256sum --check --strict; \
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"; \
    unzip -q "${archive}" -d /tmp/android-command-line-tools; \
    mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest"; \
    rm -rf "${archive}" /tmp/android-command-line-tools; \
    sdkmanager --version

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager \
        "platform-tools" \
        "platforms;android-${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}"

COPY scripts/container-entrypoint.sh /usr/local/bin/properpcloud-gradle
RUN chmod 0755 /usr/local/bin/properpcloud-gradle \
    && mkdir -p /workspace /gradle-cache /tmp/properpcloud-home \
    && chmod 0777 /gradle-cache /tmp/properpcloud-home

WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/properpcloud-gradle"]
CMD [":app:assembleDebug"]
