# Third-party notices

## Natural Earth

The generated board derives from Natural Earth `ne_50m_admin_0_countries` and `ne_10m_admin_1_states_provinces`, retrieved from the [Natural Earth vector repository](https://github.com/nvkelso/natural-earth-vector) at revision `ca96624a56bd078437bca8184e78163e5039ad19`. Natural Earth data is in the public domain. See [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/).

The generator groups countries and administrative subdivisions, simplifies coastlines, fills internal lakes, and omits subpixel islands and remote subantarctic land. The board's regions are game design choices rather than a geopolitical reference map.

## Fonts

Noto Sans Regular and Noto Serif Regular are bundled under the SIL Open Font License 1.1. The copyright and license notice is included at `assets/fonts/LICENSE.txt`.

## Runtime libraries

The game links these Rust crates (and their dependencies, all under permissive licenses):

- macroquad and miniquad (window, input, rendering, audio; MIT OR Apache-2.0) — https://github.com/not-fl3/macroquad. The Android activity glue (`MainActivity.java`, `QuadNative.java`) is miniquad's own, compiled into the APK.
- fontdue (font rasterization; MIT OR Apache-2.0 OR Zlib) — https://github.com/mooman219/fontdue
- quad-snd (audio; MIT OR Apache-2.0) — https://github.com/not-fl3/quad-snd
- serde and serde_json (saves and board data; MIT OR Apache-2.0) — https://serde.rs
- tempfile (tests only; MIT OR Apache-2.0)

Run `cargo tree` for the full dependency list.

## Board generator (optional)

`tools/board-gen` additionally uses GEOS (LGPL-2.1, compiled from source via the `geos`/`geos-sys`/`geos-src` crates; MIT) — https://libgeos.org; `sha2` and `ureq` (MIT OR Apache-2.0). None of these are part of the game or the APK.

## Game audio

The short dice and conquest WAV effects are original procedurally synthesized sounds generated for this project. No external sound recordings are bundled.

## Artwork

All nautical decorations and UI artwork are original vector drawings made for this board. Risk is mentioned only to describe the requested game style; no Risk logo, board image, or proprietary artwork is included.

## Android build

The APK is built with the Android SDK and NDK at build time; SDK tools, emulator images and signing keys are not included in this repository.
