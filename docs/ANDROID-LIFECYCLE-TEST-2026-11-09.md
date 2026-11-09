# Android graphics lifecycle test — 2026-11-09

## Device result

The user installed the debug APK produced by workflow run
[42562262626](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42562262626)
(commit `0779f938ee53`) and reported that the application behaved normally:
the graphics integration opened and the app did not close unexpectedly.

This is a successful user-reported lifecycle check for the tested path. It is
not evidence that a ROM executes or that a game frame has been rendered.

## What the build contains

- EGL shutdown guard: refuse graphics teardown without a current EGL context.
- Activity destruction queues native shutdown to the GLSurfaceView GL thread
  and waits up to 1.5 seconds before pausing the surface.
- Video extension callback logs presentation attempts and EGL swap failures.

## Execution remains intentionally disabled

`nativeExecuteCore()` still returns a safety-gate message before calling
`M64CMD_EXECUTE`. The earlier device attempt closed the app, so this test did
not authorize enabling the synchronous emulation loop.

The follow-up source cleanup in commit `98644c0e50d7` removes the unreachable
execution code below that unconditional return. This makes the safety gate
explicit and avoids presenting dead code as a usable execution path.

## Next diagnostic stage

Before enabling `M64CMD_EXECUTE`, investigate the native Core/GLideN64
initialization and frame lifecycle using controlled, non-executing checks.
Keep the following distinctions explicit:

- EGL context validity is confirmed for the tested GL thread.
- The app's open/close lifecycle passed the user's latest test.
- ROM open and GFX plugin attach were previously reported successful.
- No first game frame has yet been confirmed.
- Do not ask the user to start a ROM while the execution gate remains closed.
