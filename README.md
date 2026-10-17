# libass-android

A fork of [libass-android](https://github.com/peerless2012/libass-android) by peerless2012, which
wraps [libass](https://github.com/libass/libass) for Android. This fork optimizes rendering for
Android TV, where most devices run a 32-bit ARM userspace on slow cores.

## Patches

Build:

- NDK 28.2. The autotools dependencies (libass, FriBidi, libunibreak, Fontconfig, Expat) build at
  `-O2`; upstream builds them at `-O0`.
- libass is rebuilt on every build and linked as an imported archive, so source edits always reach
  the AAR.
- `bootstrap.sh` generates the autotools files after cloning.

libass 0.17.5:

- On armv7, the cache lookup avoids 64-bit division and `ass_lrint` compiles to one `vcvtr`.
- Glyph runs that cannot reach the frame are skipped before rasterizing.
- The NEON kernels are enabled on 32-bit ARM.
- Parsed override tags are cached by event text.

`lib_ass_kt` and `lib_ass_media`:

- Subtitle chunks are queued while a render holds the lock, so loading never waits on rendering.
- A frame is blended on the CPU into one premultiplied RGBA canvas instead of one texture per image.
- The overlay views render that canvas on a worker thread.

The existing `ass-kt` and `ass-media` APIs are unchanged; the canvas render is an addition.

## Build

Needs autoconf, automake, libtool, autopoint, gperf, pkg-config, the Android SDK with CMake
3.22.1, and NDK 28.2.13676358.

```
./bootstrap.sh
./gradlew :lib_ass_kt:assembleRelease :lib_ass_media:assembleRelease
```

The AARs land in `lib_ass_kt/build/outputs/aar/` and `lib_ass_media/build/outputs/aar/`.
