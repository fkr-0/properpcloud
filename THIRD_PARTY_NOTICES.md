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

The complete redistributed license texts are included at:

- `LICENSES/Apache-2.0.txt`;
- `LICENSES/LGPL-2.1-or-later.txt`.

The jaudiotagger dependency remains behind the replaceable `AudioTagToolkit`
adapter. properpcloud does not claim jaudiotagger code as MIT-licensed project
code and does not expose its implementation types through the public domain
model. Every `0.1.2+` GitHub release attaches the exact checksum-verified
`jaudiotagger-3.0.1-sources.jar`; the tagged repository contains the complete
properpcloud source and Gradle build scripts needed to rebuild against a modified
compatible library.

## Build and test dependencies

Build-only and test-only components are not redistributed as part of the APK,
but their license terms still apply to their use in the build environment.
The release evidence records the resolved dependency graph so this notice can
be reviewed whenever dependencies change.

This file is a curated summary, not a substitute for the complete license text
or copyright notices supplied by each upstream project.
