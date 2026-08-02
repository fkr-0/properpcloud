# Third-party notices

properpcloud's original source code is licensed under the MIT License.

The Android application links to third-party libraries. Each library remains
under its own license; the project license does not replace those terms.

## Runtime dependencies

| Component | Use | License |
|---|---|---|
| AndroidX Media3 | Playback and media sessions | Apache License 2.0 |
| AndroidX libraries | Android UI and lifecycle integration | Apache License 2.0 |
| Kotlin standard library | Kotlin runtime | Apache License 2.0 |
| kotlinx.coroutines | Structured concurrency | Apache License 2.0 |
| pCloud Java/Android SDK | pCloud API and Android authorization | Apache License 2.0 |
| jaudiotagger | Local audio-tag inspection and staged editing | GNU LGPL 2.1 or later |

The complete redistributed license texts are bundled with this APK as
`Apache-2.0.txt` and `LGPL-2.1-or-later.txt` assets.

The jaudiotagger dependency remains behind a replaceable metadata adapter;
properpcloud's original source remains MIT-licensed. The corresponding GitHub
release attaches the exact checksum-verified jaudiotagger 3.0.1 source archive,
and the tagged project contains the Gradle build scripts needed to rebuild it.

## Build and test dependencies

Build-only and test-only components are not redistributed as part of the APK,
but their license terms still apply to their use in the build environment.
The release evidence records the resolved dependency graph so this notice can
be reviewed whenever dependencies change.

This file is a curated summary, not a substitute for the complete license text
or copyright notices supplied by each upstream project.
