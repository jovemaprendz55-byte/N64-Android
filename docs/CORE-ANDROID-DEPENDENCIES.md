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

## Build-system findings confirmed against the pinned Makefile

A second pass over the complete source-selection and link sections of
`projects/unix/Makefile` found several details that must be carried into the
Android port:

- The upstream source list is explicit. It includes the API/frontend,
  backends, device/RCP/R4300 implementation, main loop, plugin loader,
  dummy plugins, Unix OS abstraction, and bundled MD5/minizip sources.
  A CMake port must mirror this list deliberately; recursively globbing all
  source files would incorrectly include platform-specific and optional code.
- The Makefile recognizes `aarch64` and selects the ARM64 dynamic recompiler
  path when `NEW_DYNAREC=1`. It also emits an "not officially supported"
  warning for ARM64. This is not proof of Android compatibility: the
  generated ARM64 assembly, NDK toolchain flags, and runtime behavior still
  require build/device validation.
- Vulkan is enabled by default on non-macOS builds unless `VULKAN=0`.
  The first Android Core-only build must explicitly decide this option rather
  than accidentally inheriting the Unix default.
- `USE_GLES=1` defines `USE_GLES`, links GLESv2, and forces `OSD=0`.
  It does not provide a video plugin or a complete Android EGL/context
  lifecycle.
- `OSD=0` removes the FreeType/OGLFT/OpenGL OSD source and link requirements.
  `NETPLAY` is also off unless explicitly enabled, so SDL2_net and netplay
  sources are not needed for the first local-ROM milestone.
- The Makefile's Linux branch selects `dynamiclib_unix.c` and
  `files_unix.c`, and links `-ldl`. Android uses Bionic rather than glibc;
  the availability of similarly named APIs does not establish that the
  Unix file-storage behavior or plugin search paths are suitable on Android.

## First Android build profile (derived, still unvalidated)

The initial CMake port should target only `arm64-v8a`, use the NDK's Clang
toolchain, disable netplay and OSD, and explicitly set the Vulkan policy.
It must use Android-built SDL2, libpng, and zlib targets, plus the pinned
upstream minizip fallback with its notices retained. The Core source list
should be transcribed from the pinned Makefile and reviewed file-by-file.
The Android build must not invoke the Unix Makefile's host compiler detection
as though Android were ordinary desktop Linux.

No Core source has been imported by this documentation change. The current
APK workflow still validates only `libn64bridge.so`; a separate CI milestone
will be required to prove that `libmupen64plus.so` actually compiles.

## Historical Android port examined (reference only)

A historical Android build recipe was located in the Mupen64Plus AE repository:
- Repository: `https://github.com/mupen64plus-ae/mupen64plus-ae`
- Inspected repository revision: `33bf7021549d7873e976884370fbd1a8e7333bc7`
- Files inspected: `mupen64plus-core/Android.mk`,
  `mupen64plus-core/mupen64plus-core.mk`,
  `mupen64plus-core/Application.mk`, and
  `mupen64plus-core/upstream/.gitrepo`.
- Its embedded Core provenance points to
  `mupen64plus-ae/mupen64plus-core` commit
  `3c4182f7473ba77e4fa1145f4de0eae098b12972`, not the pinned upstream
  revision used by this project.

The old recipe is useful as evidence that an Android NDK port has previously
been attempted, and it confirms some practical Android-specific choices:
`arm64-v8a` selects `NEW_DYNAREC=4` and
`src/device/r4300/new_dynarec/arm64/linkage_arm64.S`; it also uses
`api_export.ver` and generates architecture-specific assembly definition
headers. However, it is an old ndk-build recipe, not a drop-in CMake solution.
It contains a legacy SDL stub approach, different source selection, and
Android-specific flags whose assumptions must not be copied blindly.

**Decision:** use this recipe only as a cross-check for ARM64 dynarec and
generated assembly requirements. Do not import its modified Core snapshot,
frontend code, SDL stub, or Android.mk into this project. Keep the Core source
of record pinned to the official upstream revision documented in
`docs/LICENSING.md`, then port and validate each required adaptation here.

## Explicit Core source manifest (implementation groundwork)

Added `cmake/Mupen64PlusCoreSources.cmake` for the pinned upstream revision
`a9ca8ec0b7decd17390c76c6341cf423d5d79321`. It transcribes the required
Core source selection from that revision's `projects/unix/Makefile`, adds
the ARM64 `NEW_DYNAREC` implementation and linkage assembly, and lists the
bundled MD5/minizip sources separately. It deliberately uses explicit paths
and fails configuration if a pinned source file is absent; it does not use
recursive globbing.

This is source-manifest groundwork, **not yet a Core build**. The manifest
intentionally leaves out `src/osal/files_unix.c`: Android needs a dedicated
storage/path implementation rather than inheriting desktop Unix assumptions.
The manifest now selects upstream `src/osal/files_unix.c` as well as
`dynamiclib_unix.c`. Android's Bionic libc provides the POSIX file and `dlopen`
interfaces used by these backends. Before `CoreStartup`, the Android frontend
must set `HOME` to the app's private files directory (and may set the XDG
variables there too); this gives the existing OSAL a writable, app-scoped
configuration/data/cache root. This is a deliberate reuse of upstream code,
not a claim that Android storage permissions or SAF document URIs are handled.

The Android SDL2 lifecycle, dependency targets (SDL2, libpng, zlib), and
generated ARM64 assembly-definition headers also remain to be integrated.
The existing APK workflow still does not compile this manifest or the Core.
