# ADR 0001: Use the official API instead of forking the Android app

- Status: accepted
- Date: 2026-08-01

## Context

The production pCloud Android application's source is not publicly available. The official Java/Android SDK and HTTP API are public and sufficiently complete for folder browsing and streaming.

## Decision

Implement an independent client against the official SDK. Do not decompile, patch, redistribute, or depend on private implementation details of the pCloud Android APK.

## Consequences

Positive:

- legal and technical boundary is clear;
- no fragile APK patching;
- exact folder-first UX is possible;
- source adapters can be reused upstream.

Negative:

- authentication, browser UI, playback, caching, and persistence must be implemented;
- feature parity with pCloud file management is not automatic.
