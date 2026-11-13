# N64 Android — Vulkan renderer status

Branch: `vulkan`

## Current verified state

- Android `SurfaceView` is connected to a native Vulkan surface.
- The native backend creates the Vulkan instance, Android surface, physical/logical device, graphics/present queues, swapchain, image views, render pass, framebuffers, command buffers, synchronization objects, and graphics pipeline.
- The graphics pipeline draws a full-screen textured quad.
- A CPU-generated RGBA8888 test texture is uploaded through a Vulkan staging buffer and sampled by the fragment shader. This is a renderer test, not output from a ROM.
- `n64-video-frame.h` defines the CPU frame contract: RGBA8888, width, height, byte stride, frame number, and callback lifetime rules.
- `n64VulkanSubmitVideoFrame()` validates a frame, honors its source stride, copies it into staging memory, uploads it to the Vulkan image, and requests presentation.
- `n64VulkanVideoFrameCallback()` matches the `N64VideoFrameCallback` signature and is ready to be registered by a compatible N64 core.
- The current texture contract is fixed at 256x192 RGBA8888. Other dimensions are rejected.
- Upload and presentation are synchronous and wait for queue completion. This is a correctness-first bridge, not a frame-pacing or performance implementation.
- Commit `178a62326c14b627124880d147ea2aaab849198b` was built successfully by GitHub Actions run `43336521993`. The workflow verified that the APK contains the Vulkan native library and excludes the legacy EGL/OpenGL renderer libraries.

## Not implemented yet

- No N64 CPU/RSP/RDP emulation core is connected to this renderer.
- No game-produced framebuffer reaches the Vulkan texture.
- The generated texture is only a renderer test; selecting a ROM does not start emulation.
- Audio playback and audio-core integration are not implemented in this branch.
- The callback is not optimized for continuous 60 FPS rendering.
- Dynamic texture dimensions and swapchain recreation on surface resize are not implemented.

## Next implementation milestones

1. Generalize the Vulkan video texture and upload path to accept real N64 framebuffer dimensions, including safe image/view/descriptor recreation when dimensions change.
2. Add a core-facing video adapter that converts the selected core's framebuffer format and dimensions into the documented RGBA8888 frame contract.
3. Select and integrate an N64 emulation core and a Vulkan-compatible video/RDP path. Do not use a GLideN64/OpenGL renderer as a substitute for this Vulkan renderer.
4. Register `n64VulkanVideoFrameCallback()` with the running core and verify that actual ROM frames reach the Vulkan texture.
5. Replace per-frame staging allocation and queue-idle waits with persistent staging resources and correct fence/semaphore-based frame pacing.
6. Handle Android surface resize/recreation and lifecycle transitions.
7. Integrate audio separately and validate synchronization between emulation, video, and audio.

## Safety rules for future changes

- Work only on branch `vulkan`; do not modify `main`.
- Keep the Vulkan APK free of EGL/OpenGL renderer dependencies.
- Never describe the test texture as a running N64 game.
- After each code push, inspect the matching GitHub Actions run and report its actual result.
- Ask the user to install an APK only when a new successful build artifact exists.
