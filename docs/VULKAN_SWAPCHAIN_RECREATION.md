# Vulkan swapchain recreation plan

## Current behavior (branch `vulkan`)

The renderer currently treats swapchain resources as one teardown unit. A surface-size change reported by Android causes `MainActivity` to call `nativeInitialize` again; native initialization shuts down the logical device and creates the Vulkan instance/device/surface path again. `VK_ERROR_OUT_OF_DATE_KHR` currently pauses the renderer instead of rebuilding the swapchain in place.

## Resource ownership found in `native-vulkan.cpp`

### Surface/device lifetime (must survive an ordinary resize)
- `VkInstance` and Android `VkSurfaceKHR`
- `VkPhysicalDevice`, `VkDevice`, graphics/present queues and queue-family indices
- Loaded surface/swapchain function pointers

### Swapchain-dependent resources (rebuild on resize)
- `VkSwapchainKHR`, swapchain images and image views
- Render pass (its attachment format is the selected swapchain format)
- Graphics pipeline (created against that render pass)
- Framebuffers (one per swapchain image view)
- Command pool and recorded command buffers (reference framebuffers/render pass)
- Acquire/present semaphores and frame fence (recreate after the device is idle)

### Video resources (should survive a swapchain-only resize)
- CPU video-frame contract and latest frame pixels (the CPU copy must be retained by the future frame producer)
- Video image, image memory and image view
- Video sampler, descriptor-set layout, descriptor pool and descriptor set
- Pipeline layout is currently created with the video descriptor layout; it is pipeline-side state and should be recreated together with the graphics pipeline unless it is deliberately split.

## Required destruction order

After a successful `vkDeviceWaitIdle`:
1. Destroy framebuffers.
2. Destroy command pool (implicitly frees its command buffers).
3. Destroy graphics pipeline.
4. Destroy pipeline layout.
5. Destroy render pass.
6. Destroy swapchain image views.
7. Destroy the old swapchain.
8. Destroy/recreate frame synchronization objects.

Do not destroy swapchain images directly; they are owned by the swapchain. Do not destroy the logical device, queues, physical device, instance, or surface for a normal resize.

## Required recreation sequence

1. Query fresh surface capabilities, formats and present modes.
2. Choose a supported surface format and a non-zero extent.
3. Create a replacement swapchain. When replacing an existing swapchain, pass the old handle in `VkSwapchainCreateInfoKHR::oldSwapchain`; only retire old resources after the new swapchain is successfully created.
4. Rebuild swapchain image views.
5. Rebuild render pass and graphics pipeline if the surface format changed (rebuilding both on every resize is the simpler first safe implementation).
6. Rebuild framebuffers and command buffers using the actual swapchain extent, not `ANativeWindow_getWidth/Height` as a substitute.
7. Recreate frame synchronization objects after the device is idle.
8. Clear the renderer-faulted state only after every required resource has been recreated successfully, then present a frame.

## Important correctness constraints

- Never call the normal frame-present path recursively from swapchain creation.
- A failed partial recreation must leave the renderer faulted and must not expose partially initialized handles to the video callback.
- If `vkDeviceWaitIdle` fails, do not destroy resources that may still be in use; keep the renderer faulted for full surface/device teardown.
- Handle both `VK_ERROR_OUT_OF_DATE_KHR` and `VK_SUBOPTIMAL_KHR` by scheduling recreation outside the active acquire/submit/present sequence. Do not destroy/recreate swapchain resources while a frame is being submitted.
- Surface loss (`VK_ERROR_SURFACE_LOST_KHR`) is different from an ordinary resize and may require recreating the Android surface.
- Keep the existing Android lifecycle fallback until in-place recreation is tested on the Poco C85.

## Implementation steps

1. Split swapchain-dependent cleanup from video-texture/descriptor cleanup.
2. Store the selected swapchain extent explicitly and use it for viewport, scissor, framebuffer and render-area dimensions.
3. Add a non-recursive `recreateSwapchainLocked` transaction that waits idle, creates replacement resources, and commits only after success.
4. Route OUT_OF_DATE/SUBOPTIMAL results into a pending-recreation state; execute recreation at a safe point.
5. Test portrait/landscape changes, Home/resume, repeated resize, and close/reopen on physical Android hardware.
6. Only after lifecycle stability, remove the full-device reinitialization fallback.

## Current scope boundary

This work is renderer lifecycle preparation only. The Vulkan branch still displays a generated CPU test texture; the Nintendo 64 emulation core, ROM execution and audio are not connected to this renderer yet.
