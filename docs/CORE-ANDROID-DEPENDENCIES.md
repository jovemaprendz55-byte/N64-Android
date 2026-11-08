# Mupen64Plus Core: Android dependency and build audit

## Scope and source provenance

Inspected the Unix build rules from the exact upstream revision already pinned by this project:

- Upstream: https://github.com/mupen64plus/mupen64plus-core
- Revision: `a9ca8ec0b7decd17390c76c6341cf423d5d79321`
- Relevant file: `projects/unix/Makefile`
- License: the upstream `LICENSES` file declares the Core GPL-2.0; retain all per-file notices and separately audit bundled components.

This is a build-requirement audit, not a claim that the Core has been compiled or linked for Android.

## Dependencies exposed by the Unix Makefile

| Dependency | Upstream build behavior | Android port decision |
|---|---|---|
| zlib | Required; Makefile fails if pkg-config cannot find it (except macOS system handling) | Prefer NDK/system-compatible zlib only after verifying headers and link target; otherwise build a pinned compatible source |
| libpng | Required by the Unix build | Pin and build an Android-compatible libpng; record its license and zlib dependency |
| SDL2 | Required by the Unix build | Use a pinned Android SDL2 build initially unless a complete, tested Core platform adapter removes the relevant calls |
| SDL2_net | Required only when NETPLAY=1 | Disable netplay for the first local-ROM milestone; do not add SDL2_net prematurely |
| minizip | Uses system minizip if found; otherwise compiles bundled minizip sources with encryption disabled | Prefer bundled upstream fallback for a reproducible first build; retain its source notices |
| FreeType | Required when OSD=1 | First build may set OSD=0 if supported by upstream configuration; otherwise pin/build FreeType |
| pthread | Linked in some Unix platform branches | Use Android NDK/Bionic pthread support; verify the final link |
| dlopen/dlsym | Unix dynamic-library backend includes `dlfcn.h` | Android Bionic provides this API; test app-private plugin loading and exported symbols on device |
| GLES | Optional build path controlled by USE_GLES | Video plugin/context integration is a separate milestone; Core build alone does not provide graphics |

The upstream Makefile's default options and platform branches must be read before translating flags. Do not copy the Unix Makefile wholesale into Android CMake.

## Minimal first-build profile (proposal, not yet implemented)

- ABI: `arm64-v8a` only, matching the current app target.
- Disable optional netplay.
- Disable OSD initially if the pinned Makefile permits it without removing required functionality.
- Use pinned Android builds of SDL2 and libpng, plus a documented zlib/minizip strategy.
- Build the Core as a shared library with its public API symbols exported.
- Keep plugin binaries out of the first Core-only build; verify the Core shared library first.
- Preserve upstream source and license files, and maintain a patch list.

## Important integration risks

1. The existing Android app currently builds only `libn64bridge.so`; this is not the Mupen64Plus Core.
2. The current `CMakeLists.txt` does not yet compile upstream Core sources.
3. The Core's frontend lifecycle and SDL calls must be handled intentionally; a successful link is not proof that startup works.
4. Dynamic plugin loading must use the exact plugin ABI from the pinned headers. The Core's plugin loader resolves required symbols at runtime.
5. ROM open/execute must be driven from a native worker thread and must follow Core state restrictions.
6. No copyrighted game ROM or firmware is included in this repository. Device testing must use user-owned, legally obtained content.

## Next implementation step

Before adding third-party source files, decide and pin exact Android dependency revisions and licenses. Then import the Core source at the pinned commit with its notices intact, add a separate CMake target, and make CI build `libmupen64plus.so` for `arm64-v8a`. Keep the existing app bridge target working in parallel.

## Verification status (2026-11-07 project session)

- Existing Android skeleton build: passed in runs `42429727431` and `42429735962`.
- Both runs produced the `n64-android-debug` artifact (812,506 bytes).
- These runs compile the current diagnostic bridge only.
- Mupen64Plus Core / third-party Android dependencies: not yet imported or built.
- GoldenEye 007 execution: not tested and not claimed.
