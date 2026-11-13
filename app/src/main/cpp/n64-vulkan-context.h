#pragma once

#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

// Returns borrowed handles owned by the Android Vulkan renderer.
// The caller must not destroy either handle.
bool n64VulkanGetRdpContext(VkInstance* instance, VkPhysicalDevice* gpu);

#ifdef __cplusplus
}
#endif
