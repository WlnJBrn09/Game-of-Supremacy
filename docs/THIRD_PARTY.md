# Third-party notices

## Natural Earth

The generated board derives from Natural Earth `ne_50m_admin_0_countries` and `ne_10m_admin_1_states_provinces`, retrieved from the [Natural Earth vector repository](https://github.com/nvkelso/natural-earth-vector) at revision `ca96624a56bd078437bca8184e78163e5039ad19`. Natural Earth data is in the public domain. See [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).

The generator groups countries and administrative subdivisions, simplifies coastlines, fills internal lakes, and omits subpixel islands and remote subantarctic land. The board's regions are game design choices rather than a geopolitical reference map.

## Fonts

Noto Sans Regular and Noto Serif Regular are bundled under the SIL Open Font License 1.1. The copyright and license notice is included at `src/main/resources/fonts/LICENSE.txt`.

## Runtime and build libraries

- libGDX: Apache License 2.0 — https://github.com/libgdx/libgdx
- LWJGL: BSD license — https://www.lwjgl.org/license
- Maven Wrapper: Apache License 2.0 — https://github.com/apache/maven-wrapper
- JUnit (test dependency): Eclipse Public License 2.0 — https://junit.org/junit5/
- Shapely (optional map-generation dependency): BSD 3-Clause — https://github.com/shapely/shapely

Upstream notices packaged in dependency JARs remain in the bundled build. All nautical decorations and UI artwork are original vector drawings made for this board. Risk is mentioned only to describe the requested game style; no Risk logo, board image, or proprietary artwork is included.

## Game audio

All WAV effects (dice, conquest, placement, victory and defeat) are original procedurally synthesized sounds generated for this project; `tools/generate_sounds.py` regenerates the last three. No external sound recordings are bundled.

## Android build

The Android project uses Android Gradle Plugin and the Android SDK at build time; SDK tools, emulator images and signing keys are not included in this repository. Core library desugaring uses Google's `desugar_jdk_libs` (GPLv2 with Classpath Exception) — https://github.com/google/desugar_jdk_libs. AndroidX support libraries are Apache License 2.0 — https://android.googlesource.com/platform/frameworks/support/.
