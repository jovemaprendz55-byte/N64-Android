# N64 Android — Vulkan renderer status

Branch: `vulkan`

## Current verified state

- Android `SurfaceView` is connected to a native Vulkan surface.
- The native backend creates a Vulkan instance, Android surface, physical/logical device, graphics/present queues, swapchain, image views, render pass, framebuffers, command buffers, synchronization objects, and a graphics pipeline.
- The current test pipeline draws a colored triangle. This was installed and visually confirmed on the user's Poco C85.
- `n64-video-frame.h` defines the CPU-side frame contract: RGBA8888, dimensions, byte stride, frame number, and callback lifetime rules.
- CI run `43333804650` for commit `c7121a3` completed successfully. The APK build and check excluding legacy EGL/OpenGL libraries passed.

## Not implemented yet

- No N64 CPU/RSP/RDP core is currently connected to this Vulkan renderer.
- No game-produced framebuffer is uploaded to a Vulkan image yet.
- The triangle is only a renderer test; it is not output from a ROM.
- Audio playback/core integration is not implemented in this Vulkan branch.
- ROM selection/validation exists in the Android shell, but selecting a ROM does not start emulation.

## Next implementation milestones

1. Replace the triangle-only shaders/pipeline with a textured full-screen quad.
2. Create a Vulkan RGBA8 sampled image, image view, sampler, descriptor set, and staging buffer.
3. Upload a generated CPU test frame (not a ROM frame) through the staging buffer, with explicit image layout transitions and transfer synchronization.
4. Draw that texture through the Vulkan pipeline and verify the result on the Poco C85.
5. Connect the `N64VideoFrameCallback` contract to the upload path, including stride and frame-size validation.
6. Select and integrate an N64 emulation core and a Vulkan-compatible RDP/video path. Do not use a GLideN64/OpenGL renderer as a substitute for the Vulkan renderer.
7. Integrate audio separately, then add frame pacing, resize/recreation handling, and performance profiling.

## Safety rules for future changes

- Work only on branch `vulkan`; do not modify `main`.
- Keep the Vulkan APK free of EGL/OpenGL renderer dependencies.
- Do not describe the current triangle as a running N64 game.
- After each code push, inspect the matching GitHub Actions run and report its actual result.
- Ask the user to install an APK only when a new successful build artifact exists.
