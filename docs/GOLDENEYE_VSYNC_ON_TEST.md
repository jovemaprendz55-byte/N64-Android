# GoldenEye VSync-on comparison

## Purpose
Compare the current adaptive-presentation build with exactly one graphics setting changed: GLideN64 vertical synchronization.

## Single change
- Set `config.video.verticalSync = 1` for Android.
- Keep the adaptive VI presentation experiment, framebuffer settings, renderer, audio plugin, ROM handling, and other build inputs unchanged from the previous test branch.

## Device test
Use the same GoldenEye 007 ROM, same scene, same device, same screen resolution, and similar thermal/battery conditions. Record:
- perceived smoothness and audio stability
- `N64AndroidVideo PERF` fps, work_avg_ms, swap_avg_ms
- GLideN64 `N64ANDROID_FRAME_SKIP` messages if present

## Interpretation
If swap_avg_ms rises or FPS becomes more unstable, revert this VSync change. If presentation becomes steadier without audio regression, keep it as a candidate. This is an isolated experiment and must not be merged into the main branch without device validation.
