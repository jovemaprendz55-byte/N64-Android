#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include "triangle_shaders.h"
#include "n64-video-frame.h"

#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* TAG = "N64AndroidVulkan";
std::mutex g_mutex;
VkInstance g_instance = VK_NULL_HANDLE;
VkSurfaceKHR g_surface = VK_NULL_HANDLE;
ANativeWindow* g_window = nullptr;
VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
VkDevice g_device = VK_NULL_HANDLE;
VkQueue g_graphicsQueue = VK_NULL_HANDLE;
VkQueue g_presentQueue = VK_NULL_HANDLE;
uint32_t g_graphicsFamily = UINT32_MAX;
uint32_t g_presentFamily = UINT32_MAX;
VkSwapchainKHR g_swapchain = VK_NULL_HANDLE;
VkFormat g_swapchainFormat = VK_FORMAT_UNDEFINED;
std::vector<VkImage> g_swapchainImages;
std::vector<VkImageView> g_swapchainViews;
VkRenderPass g_renderPass = VK_NULL_HANDLE;
VkPipelineLayout g_pipelineLayout = VK_NULL_HANDLE;
VkPipeline g_graphicsPipeline = VK_NULL_HANDLE;
std::vector<VkFramebuffer> g_framebuffers;
VkCommandPool g_commandPool = VK_NULL_HANDLE;
std::vector<VkCommandBuffer> g_commandBuffers;
VkSemaphore g_imageAvailable = VK_NULL_HANDLE;
VkSemaphore g_renderFinished = VK_NULL_HANDLE;
VkFence g_frameFence = VK_NULL_HANDLE;
VkImage g_videoImage = VK_NULL_HANDLE;
VkDeviceMemory g_videoImageMemory = VK_NULL_HANDLE;
VkImageView g_videoImageView = VK_NULL_HANDLE;
VkSampler g_videoSampler = VK_NULL_HANDLE;
VkDescriptorSetLayout g_videoSetLayout = VK_NULL_HANDLE;
VkDescriptorPool g_videoDescriptorPool = VK_NULL_HANDLE;
VkDescriptorSet g_videoDescriptorSet = VK_NULL_HANDLE;
uint32_t g_videoWidth = 0;
uint32_t g_videoHeight = 0;
bool g_rendererFaulted = false;

PFN_vkDestroySurfaceKHR pDestroySurface = nullptr;
PFN_vkGetPhysicalDeviceSurfaceSupportKHR pGetSurfaceSupport = nullptr;
PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR pGetSurfaceCapabilities = nullptr;
PFN_vkGetPhysicalDeviceSurfaceFormatsKHR pGetSurfaceFormats = nullptr;
PFN_vkGetPhysicalDeviceSurfacePresentModesKHR pGetPresentModes = nullptr;
PFN_vkCreateSwapchainKHR pCreateSwapchain = nullptr;
PFN_vkDestroySwapchainKHR pDestroySwapchain = nullptr;
PFN_vkGetSwapchainImagesKHR pGetSwapchainImages = nullptr;

void destroySwapchainLocked() {
    if (g_device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_device);
        for (VkFramebuffer framebuffer : g_framebuffers)
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(g_device, framebuffer, nullptr);
        g_framebuffers.clear();
        if (g_graphicsPipeline != VK_NULL_HANDLE) vkDestroyPipeline(g_device, g_graphicsPipeline, nullptr);
        if (g_videoDescriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(g_device, g_videoDescriptorPool, nullptr);
        if (g_pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(g_device, g_pipelineLayout, nullptr);
        if (g_videoSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(g_device, g_videoSetLayout, nullptr);
        if (g_videoSampler != VK_NULL_HANDLE) vkDestroySampler(g_device, g_videoSampler, nullptr);
        if (g_videoImageView != VK_NULL_HANDLE) vkDestroyImageView(g_device, g_videoImageView, nullptr);
        if (g_videoImage != VK_NULL_HANDLE) vkDestroyImage(g_device, g_videoImage, nullptr);
        if (g_videoImageMemory != VK_NULL_HANDLE) vkFreeMemory(g_device, g_videoImageMemory, nullptr);
        g_videoDescriptorPool = VK_NULL_HANDLE;
        g_videoSetLayout = VK_NULL_HANDLE;
        g_videoDescriptorSet = VK_NULL_HANDLE;
        g_videoSampler = VK_NULL_HANDLE;
        g_videoImageView = VK_NULL_HANDLE;
        g_videoImage = VK_NULL_HANDLE;
        g_videoImageMemory = VK_NULL_HANDLE;
        g_videoWidth = 0;
        g_videoHeight = 0;
        g_graphicsPipeline = VK_NULL_HANDLE;
        g_pipelineLayout = VK_NULL_HANDLE;
        if (g_renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(g_device, g_renderPass, nullptr);
        g_renderPass = VK_NULL_HANDLE;
        if (g_commandPool != VK_NULL_HANDLE)
            vkDestroyCommandPool(g_device, g_commandPool, nullptr);
        g_commandPool = VK_NULL_HANDLE;
        g_commandBuffers.clear();
        if (g_imageAvailable != VK_NULL_HANDLE)
            vkDestroySemaphore(g_device, g_imageAvailable, nullptr);
        if (g_renderFinished != VK_NULL_HANDLE)
            vkDestroySemaphore(g_device, g_renderFinished, nullptr);
        if (g_frameFence != VK_NULL_HANDLE) vkDestroyFence(g_device, g_frameFence, nullptr);
        g_imageAvailable = VK_NULL_HANDLE;
        g_renderFinished = VK_NULL_HANDLE;
        g_frameFence = VK_NULL_HANDLE;
        auto destroyView = reinterpret_cast<PFN_vkDestroyImageView>(
            vkGetDeviceProcAddr(g_device, "vkDestroyImageView"));
        if (destroyView) {
            for (VkImageView view : g_swapchainViews) {
                if (view != VK_NULL_HANDLE) destroyView(g_device, view, nullptr);
            }
        }
        g_swapchainViews.clear();
        g_swapchainImages.clear();
        if (g_swapchain != VK_NULL_HANDLE && pDestroySwapchain) {
            pDestroySwapchain(g_device, g_swapchain, nullptr);
        }
    }
    g_swapchain = VK_NULL_HANDLE;
    g_swapchainFormat = VK_FORMAT_UNDEFINED;
}

void shutdownLocked() {
    destroySwapchainLocked();
    g_rendererFaulted = false;
    if (g_device != VK_NULL_HANDLE) vkDestroyDevice(g_device, nullptr);
    g_device = VK_NULL_HANDLE;
    g_graphicsQueue = VK_NULL_HANDLE;
    g_presentQueue = VK_NULL_HANDLE;
    g_physicalDevice = VK_NULL_HANDLE;
    g_graphicsFamily = UINT32_MAX;
    g_presentFamily = UINT32_MAX;
    pCreateSwapchain = nullptr;
    pDestroySwapchain = nullptr;
    pGetSwapchainImages = nullptr;
    if (g_instance != VK_NULL_HANDLE && g_surface != VK_NULL_HANDLE && pDestroySurface) {
        pDestroySurface(g_instance, g_surface, nullptr);
    }
    g_surface = VK_NULL_HANDLE;
    if (g_instance != VK_NULL_HANDLE) vkDestroyInstance(g_instance, nullptr);
    g_instance = VK_NULL_HANDLE;
    pDestroySurface = nullptr;
    pGetSurfaceSupport = nullptr;
    pGetSurfaceCapabilities = nullptr;
    pGetSurfaceFormats = nullptr;
    pGetPresentModes = nullptr;
    if (g_window != nullptr) ANativeWindow_release(g_window);
    g_window = nullptr;
}

bool hasInstanceExtension(const char* wanted) {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS)
        return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data()) != VK_SUCCESS)
        return false;
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, wanted) == 0) return true;
    }
    return false;
}

bool hasDeviceExtension(VkPhysicalDevice device, const char* wanted) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS)
        return false;
    std::vector<VkExtensionProperties> extensions(count);
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS)
        return false;
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, wanted) == 0) return true;
    }
    return false;
}

bool loadSurfaceFunctions() {
    pDestroySurface = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
        vkGetInstanceProcAddr(g_instance, "vkDestroySurfaceKHR"));
    pGetSurfaceSupport = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
    pGetSurfaceCapabilities = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
    pGetSurfaceFormats = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
    pGetPresentModes = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));
    return pDestroySurface && pGetSurfaceSupport && pGetSurfaceCapabilities &&
        pGetSurfaceFormats && pGetPresentModes;
}

bool selectPhysicalDevice(std::string& details) {
    uint32_t count = 0;
    VkResult status = vkEnumeratePhysicalDevices(g_instance, &count, nullptr);
    if (status != VK_SUCCESS || count == 0) {
        details = "Nenhuma GPU Vulkan encontrada.";
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    status = vkEnumeratePhysicalDevices(g_instance, &count, devices.data());
    if (status != VK_SUCCESS) {
        details = "Falha ao enumerar GPUs: " + std::to_string(status);
        return false;
    }

    for (VkPhysicalDevice candidate : devices) {
        if (!hasDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) continue;
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
        uint32_t graphics = UINT32_MAX;
        uint32_t present = UINT32_MAX;
        for (uint32_t i = 0; i < familyCount; ++i) {
            if (families[i].queueCount > 0 &&
                (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 && graphics == UINT32_MAX)
                graphics = i;
            VkBool32 supported = VK_FALSE;
            if (pGetSurfaceSupport(candidate, i, g_surface, &supported) == VK_SUCCESS &&
                supported == VK_TRUE && present == UINT32_MAX) present = i;
        }
        if (graphics == UINT32_MAX || present == UINT32_MAX) continue;
        uint32_t formatCount = 0;
        uint32_t modeCount = 0;
        if (pGetSurfaceFormats(candidate, g_surface, &formatCount, nullptr) != VK_SUCCESS ||
            pGetPresentModes(candidate, g_surface, &modeCount, nullptr) != VK_SUCCESS ||
            formatCount == 0 || modeCount == 0) continue;
        g_physicalDevice = candidate;
        g_graphicsFamily = graphics;
        g_presentFamily = present;
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        details = properties.deviceName;
        return true;
    }
    details = "Nenhuma GPU suporta gráficos, apresentação e VK_KHR_swapchain.";
    return false;
}

bool createLogicalDevice(std::string& error) {
    const float priority = 1.0f;
    std::vector<VkDeviceQueueCreateInfo> queueInfos;
    VkDeviceQueueCreateInfo graphicsInfo{};
    graphicsInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    graphicsInfo.queueFamilyIndex = g_graphicsFamily;
    graphicsInfo.queueCount = 1;
    graphicsInfo.pQueuePriorities = &priority;
    queueInfos.push_back(graphicsInfo);
    if (g_presentFamily != g_graphicsFamily) {
        VkDeviceQueueCreateInfo presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        presentInfo.queueFamilyIndex = g_presentFamily;
        presentInfo.queueCount = 1;
        presentInfo.pQueuePriorities = &priority;
        queueInfos.push_back(presentInfo);
    }
    const char* extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    info.queueCreateInfoCount = static_cast<uint32_t>(queueInfos.size());
    info.pQueueCreateInfos = queueInfos.data();
    info.enabledExtensionCount = 1;
    info.ppEnabledExtensionNames = extensions;
    VkResult status = vkCreateDevice(g_physicalDevice, &info, nullptr, &g_device);
    if (status != VK_SUCCESS) {
        error = "vkCreateDevice falhou: " + std::to_string(status);
        return false;
    }
    vkGetDeviceQueue(g_device, g_graphicsFamily, 0, &g_graphicsQueue);
    vkGetDeviceQueue(g_device, g_presentFamily, 0, &g_presentQueue);
    pCreateSwapchain = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
        vkGetDeviceProcAddr(g_device, "vkCreateSwapchainKHR"));
    pDestroySwapchain = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
        vkGetDeviceProcAddr(g_device, "vkDestroySwapchainKHR"));
    pGetSwapchainImages = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
        vkGetDeviceProcAddr(g_device, "vkGetSwapchainImagesKHR"));
    if (!pCreateSwapchain || !pDestroySwapchain || !pGetSwapchainImages) {
        error = "Driver não disponibilizou as funções VK_KHR_swapchain.";
        return false;
    }
    return true;
}


uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(g_physicalDevice, &memoryProperties);
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) != 0 &&
            (memoryProperties.memoryTypes[i].propertyFlags & properties) == properties)
            return i;
    }
    return UINT32_MAX;
}

bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage,
                  VkMemoryPropertyFlags properties, VkBuffer* buffer,
                  VkDeviceMemory* memory, std::string& error) {
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult status = vkCreateBuffer(g_device, &info, nullptr, buffer);
    if (status != VK_SUCCESS) {
        error = "vkCreateBuffer falhou: " + std::to_string(status);
        return false;
    }
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(g_device, *buffer, &requirements);
    const uint32_t type = findMemoryType(requirements.memoryTypeBits, properties);
    if (type == UINT32_MAX) {
        error = "Tipo de memória Vulkan para buffer não encontrado.";
        return false;
    }
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type;
    status = vkAllocateMemory(g_device, &allocation, nullptr, memory);
    if (status != VK_SUCCESS) {
        error = "Alocação de memória do buffer falhou: " + std::to_string(status);
        return false;
    }
    status = vkBindBufferMemory(g_device, *buffer, *memory, 0);
    if (status != VK_SUCCESS) {
        error = "vkBindBufferMemory falhou: " + std::to_string(status);
        return false;
    }
    return true;
}


bool recreateVideoTextureLocked(const N64VideoFrame& frame,
                                const uint8_t* packedPixels,
                                VkDeviceSize byteCount,
                                std::string& error) {
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(g_physicalDevice, &properties);
    if (frame.width > properties.limits.maxImageDimension2D ||
        frame.height > properties.limits.maxImageDimension2D) {
        error = "Dimensão do quadro excede o limite máximo de textura da GPU.";
        return false;
    }

    VkImage newImage = VK_NULL_HANDLE;
    VkDeviceMemory newMemory = VK_NULL_HANDLE;
    VkImageView newView = VK_NULL_HANDLE;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;

    auto cleanupNew = [&]() {
        if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(g_device, pool, nullptr);
        if (staging != VK_NULL_HANDLE) vkDestroyBuffer(g_device, staging, nullptr);
        if (stagingMemory != VK_NULL_HANDLE) vkFreeMemory(g_device, stagingMemory, nullptr);
        if (newView != VK_NULL_HANDLE) vkDestroyImageView(g_device, newView, nullptr);
        if (newImage != VK_NULL_HANDLE) vkDestroyImage(g_device, newImage, nullptr);
        if (newMemory != VK_NULL_HANDLE) vkFreeMemory(g_device, newMemory, nullptr);
    };

    VkResult status = VK_SUCCESS;
    if (!createBuffer(byteCount, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        &staging, &stagingMemory, error)) {
        cleanupNew();
        return false;
    }
    {
        void* mapped = nullptr;
        status = vkMapMemory(g_device, stagingMemory, 0, byteCount, 0, &mapped);
        if (status == VK_SUCCESS) {
            std::memcpy(mapped, packedPixels, static_cast<size_t>(byteCount));
            vkUnmapMemory(g_device, stagingMemory);
        } else {
            error = "Mapeamento do staging para nova textura falhou: " + std::to_string(status);
        }
    }

    if (status == VK_SUCCESS) {
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.extent = {frame.width, frame.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        status = vkCreateImage(g_device, &imageInfo, nullptr, &newImage);
        if (status != VK_SUCCESS)
            error = "Criação da textura redimensionada falhou: " + std::to_string(status);
    }

    if (status == VK_SUCCESS) {
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(g_device, newImage, &requirements);
        const uint32_t memoryType = findMemoryType(requirements.memoryTypeBits,
                                                   VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memoryType == UINT32_MAX) {
            status = VK_ERROR_FEATURE_NOT_PRESENT;
            error = "Memória local da GPU não encontrada para a nova textura.";
        } else {
            VkMemoryAllocateInfo allocation{};
            allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            allocation.allocationSize = requirements.size;
            allocation.memoryTypeIndex = memoryType;
            status = vkAllocateMemory(g_device, &allocation, nullptr, &newMemory);
            if (status == VK_SUCCESS)
                status = vkBindImageMemory(g_device, newImage, newMemory, 0);
            if (status != VK_SUCCESS)
                error = "Alocação/vínculo da nova textura falhou: " + std::to_string(status);
        }
    }

    VkCommandBuffer command = VK_NULL_HANDLE;
    if (status == VK_SUCCESS) {
        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        poolInfo.queueFamilyIndex = g_graphicsFamily;
        status = vkCreateCommandPool(g_device, &poolInfo, nullptr, &pool);
        if (status != VK_SUCCESS)
            error = "Criação do command pool para redimensionamento falhou: " + std::to_string(status);
    }
    if (status == VK_SUCCESS) {
        VkCommandBufferAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = pool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = 1;
        status = vkAllocateCommandBuffers(g_device, &allocate, &command);
        if (status != VK_SUCCESS)
            error = "Alocação do command buffer para redimensionamento falhou: " + std::to_string(status);
    }
    if (status == VK_SUCCESS) {
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        status = vkBeginCommandBuffer(command, &begin);
    }
    if (status == VK_SUCCESS) {
        VkImageMemoryBarrier toTransfer{};
        toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = newImage;
        toTransfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransfer);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {frame.width, frame.height, 1};
        vkCmdCopyBufferToImage(command, staging, newImage,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        VkImageMemoryBarrier toShader{};
        toShader.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        toShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.image = newImage;
        toShader.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toShader);
        status = vkEndCommandBuffer(command);
        if (status != VK_SUCCESS)
            error = "Finalização do command buffer de redimensionamento falhou: " + std::to_string(status);
    }
    if (status == VK_SUCCESS) {
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        status = vkQueueSubmit(g_graphicsQueue, 1, &submit, VK_NULL_HANDLE);
        if (status == VK_SUCCESS) status = vkQueueWaitIdle(g_graphicsQueue);
        if (status != VK_SUCCESS)
            error = "Upload da nova textura falhou: " + std::to_string(status);
    }
    if (pool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(g_device, pool, nullptr);
        pool = VK_NULL_HANDLE;
    }
    if (staging != VK_NULL_HANDLE) {
        vkDestroyBuffer(g_device, staging, nullptr);
        staging = VK_NULL_HANDLE;
    }
    if (stagingMemory != VK_NULL_HANDLE) {
        vkFreeMemory(g_device, stagingMemory, nullptr);
        stagingMemory = VK_NULL_HANDLE;
    }
    if (status == VK_SUCCESS) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = newImage;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        status = vkCreateImageView(g_device, &viewInfo, nullptr, &newView);
        if (status != VK_SUCCESS)
            error = "Image view da nova textura falhou: " + std::to_string(status);
    }
    if (status == VK_SUCCESS) {
        status = vkDeviceWaitIdle(g_device);
        if (status != VK_SUCCESS)
            error = "GPU não ficou ociosa antes da troca da textura: " + std::to_string(status);
    }
    if (status == VK_SUCCESS) {
        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = g_videoSampler;
        imageInfo.imageView = newView;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = g_videoDescriptorSet;
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(g_device, 1, &write, 0, nullptr);

        if (g_videoImageView != VK_NULL_HANDLE)
            vkDestroyImageView(g_device, g_videoImageView, nullptr);
        if (g_videoImage != VK_NULL_HANDLE)
            vkDestroyImage(g_device, g_videoImage, nullptr);
        if (g_videoImageMemory != VK_NULL_HANDLE)
            vkFreeMemory(g_device, g_videoImageMemory, nullptr);
        g_videoImage = newImage;
        g_videoImageMemory = newMemory;
        g_videoImageView = newView;
        g_videoWidth = frame.width;
        g_videoHeight = frame.height;
        newImage = VK_NULL_HANDLE;
        newMemory = VK_NULL_HANDLE;
        newView = VK_NULL_HANDLE;
        return true;
    }
    cleanupNew();
    return false;
}

bool uploadVideoFrameLocked(const N64VideoFrame& frame, std::string& error) {
    if (!n64VideoFrameIsValid(&frame)) {
        error = "Quadro N64 inválido: esperado RGBA8888 com stride válido.";
        return false;
    }
    if (g_device == VK_NULL_HANDLE || g_videoImage == VK_NULL_HANDLE) {
        error = "Textura Vulkan ainda não foi inicializada.";
        return false;
    }
    const VkDeviceSize rowBytes = static_cast<VkDeviceSize>(frame.width) * 4;
    const VkDeviceSize byteCount = rowBytes * frame.height;
    std::vector<uint8_t> packed(static_cast<size_t>(byteCount));
    for (uint32_t y = 0; y < frame.height; ++y) {
        std::memcpy(packed.data() + static_cast<size_t>(y * rowBytes),
                    frame.pixels + static_cast<size_t>(y) * frame.stride_bytes,
                    static_cast<size_t>(rowBytes));
    }

    if (frame.width != g_videoWidth || frame.height != g_videoHeight) {
        return recreateVideoTextureLocked(frame, packed.data(), byteCount, error);
    }

    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBuffer(byteCount, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        &staging, &stagingMemory, error)) {
        if (staging != VK_NULL_HANDLE) vkDestroyBuffer(g_device, staging, nullptr);
        if (stagingMemory != VK_NULL_HANDLE) vkFreeMemory(g_device, stagingMemory, nullptr);
        return false;
    }
    void* mapped = nullptr;
    VkResult status = vkMapMemory(g_device, stagingMemory, 0, byteCount, 0, &mapped);
    if (status == VK_SUCCESS) {
        std::memcpy(mapped, packed.data(), static_cast<size_t>(byteCount));
        vkUnmapMemory(g_device, stagingMemory);
    }

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    if (status == VK_SUCCESS) {
        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
        poolInfo.queueFamilyIndex = g_graphicsFamily;
        status = vkCreateCommandPool(g_device, &poolInfo, nullptr, &pool);
    }
    if (status == VK_SUCCESS) {
        VkCommandBufferAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        alloc.commandPool = pool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = 1;
        status = vkAllocateCommandBuffers(g_device, &alloc, &command);
    }
    if (status == VK_SUCCESS) {
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        status = vkBeginCommandBuffer(command, &begin);
    }
    if (status == VK_SUCCESS) {
        VkImageMemoryBarrier toTransfer{};
        toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toTransfer.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = g_videoImage;
        toTransfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransfer);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {frame.width, frame.height, 1};
        vkCmdCopyBufferToImage(command, staging, g_videoImage,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        VkImageMemoryBarrier toShader{};
        toShader.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        toShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.image = g_videoImage;
        toShader.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toShader);
        status = vkEndCommandBuffer(command);
    }
    if (status == VK_SUCCESS) {
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        status = vkQueueSubmit(g_graphicsQueue, 1, &submit, VK_NULL_HANDLE);
        if (status == VK_SUCCESS) status = vkQueueWaitIdle(g_graphicsQueue);
    }
    if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(g_device, pool, nullptr);
    vkDestroyBuffer(g_device, staging, nullptr);
    vkFreeMemory(g_device, stagingMemory, nullptr);
    if (status != VK_SUCCESS) {
        error = "Upload do quadro N64 para Vulkan falhou: " + std::to_string(status);
        return false;
    }
    return true;
}

bool createVideoTexture(std::string& error) {
    constexpr uint32_t width = 256;
    constexpr uint32_t height = 192;
    const VkDeviceSize byteCount = static_cast<VkDeviceSize>(width) * height * 4;
    std::vector<uint8_t> pixels(static_cast<size_t>(byteCount));
    N64VideoFrame testFrame{};
    testFrame.pixels = pixels.data();
    testFrame.width = width;
    testFrame.height = height;
    testFrame.stride_bytes = width * 4;
    testFrame.pixel_format = N64_VIDEO_PIXEL_FORMAT_RGBA8888;
    testFrame.frame_number = 0;
    if (!n64VideoFrameIsValid(&testFrame)) {
        error = "Quadro CPU de teste inválido.";
        return false;
    }
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            const size_t offset = (static_cast<size_t>(y) * width + x) * 4;
            pixels[offset + 0] = static_cast<uint8_t>((x * 255) / (width - 1));
            pixels[offset + 1] = static_cast<uint8_t>((y * 255) / (height - 1));
            pixels[offset + 2] = static_cast<uint8_t>(((x / 16 + y / 12) % 2) ? 220 : 35);
            pixels[offset + 3] = 255;
        }
    }

    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBuffer(byteCount, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        &staging, &stagingMemory, error)) return false;
    void* mapped = nullptr;
    VkResult status = vkMapMemory(g_device, stagingMemory, 0, byteCount, 0, &mapped);
    if (status != VK_SUCCESS) {
        error = "Mapeamento do staging buffer falhou: " + std::to_string(status);
        vkDestroyBuffer(g_device, staging, nullptr);
        vkFreeMemory(g_device, stagingMemory, nullptr);
        return false;
    }
    std::memcpy(mapped, pixels.data(), static_cast<size_t>(byteCount));
    vkUnmapMemory(g_device, stagingMemory);

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    status = vkCreateImage(g_device, &imageInfo, nullptr, &g_videoImage);
    if (status != VK_SUCCESS) {
        error = "Criação da textura Vulkan falhou: " + std::to_string(status);
        vkDestroyBuffer(g_device, staging, nullptr);
        vkFreeMemory(g_device, stagingMemory, nullptr);
        return false;
    }
    VkMemoryRequirements imageRequirements{};
    vkGetImageMemoryRequirements(g_device, g_videoImage, &imageRequirements);
    const uint32_t imageType = findMemoryType(imageRequirements.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (imageType == UINT32_MAX) {
        error = "Memória local da GPU não encontrada para textura.";
        vkDestroyBuffer(g_device, staging, nullptr);
        vkFreeMemory(g_device, stagingMemory, nullptr);
        return false;
    }
    VkMemoryAllocateInfo imageAllocation{};
    imageAllocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageAllocation.allocationSize = imageRequirements.size;
    imageAllocation.memoryTypeIndex = imageType;
    status = vkAllocateMemory(g_device, &imageAllocation, nullptr, &g_videoImageMemory);
    if (status == VK_SUCCESS)
        status = vkBindImageMemory(g_device, g_videoImage, g_videoImageMemory, 0);
    if (status != VK_SUCCESS) {
        error = "Alocação/vínculo da textura falhou: " + std::to_string(status);
        vkDestroyBuffer(g_device, staging, nullptr);
        vkFreeMemory(g_device, stagingMemory, nullptr);
        return false;
    }

    VkCommandPool transferPool = VK_NULL_HANDLE;
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    poolInfo.queueFamilyIndex = g_graphicsFamily;
    status = vkCreateCommandPool(g_device, &poolInfo, nullptr, &transferPool);
    VkCommandBuffer command = VK_NULL_HANDLE;
    if (status == VK_SUCCESS) {
        VkCommandBufferAllocateInfo allocate{};
        allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = transferPool;
        allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate.commandBufferCount = 1;
        status = vkAllocateCommandBuffers(g_device, &allocate, &command);
    }
    if (status == VK_SUCCESS) {
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        status = vkBeginCommandBuffer(command, &begin);
    }
    if (status == VK_SUCCESS) {
        VkImageMemoryBarrier toTransfer{};
        toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toTransfer.srcAccessMask = 0;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = g_videoImage;
        toTransfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransfer);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {width, height, 1};
        vkCmdCopyBufferToImage(command, staging, g_videoImage,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        VkImageMemoryBarrier toShader{};
        toShader.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        toShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toShader.image = g_videoImage;
        toShader.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toShader);
        status = vkEndCommandBuffer(command);
    }
    if (status == VK_SUCCESS) {
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        status = vkQueueSubmit(g_graphicsQueue, 1, &submit, VK_NULL_HANDLE);
        if (status == VK_SUCCESS) status = vkQueueWaitIdle(g_graphicsQueue);
    }
    if (transferPool != VK_NULL_HANDLE) vkDestroyCommandPool(g_device, transferPool, nullptr);
    vkDestroyBuffer(g_device, staging, nullptr);
    vkFreeMemory(g_device, stagingMemory, nullptr);
    if (status != VK_SUCCESS) {
        error = "Transferência CPU-GPU da textura falhou: " + std::to_string(status);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = g_videoImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    status = vkCreateImageView(g_device, &viewInfo, nullptr, &g_videoImageView);
    if (status != VK_SUCCESS) {
        error = "Image view da textura falhou: " + std::to_string(status);
        return false;
    }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.0f;
    status = vkCreateSampler(g_device, &samplerInfo, nullptr, &g_videoSampler);
    if (status != VK_SUCCESS) {
        error = "Sampler da textura falhou: " + std::to_string(status);
        return false;
    }

    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = 1;
    setLayoutInfo.pBindings = &binding;
    status = vkCreateDescriptorSetLayout(g_device, &setLayoutInfo, nullptr, &g_videoSetLayout);
    if (status != VK_SUCCESS) {
        error = "Layout do descritor de textura falhou: " + std::to_string(status);
        return false;
    }
    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo descriptorPoolInfo{};
    descriptorPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    descriptorPoolInfo.maxSets = 1;
    descriptorPoolInfo.poolSizeCount = 1;
    descriptorPoolInfo.pPoolSizes = &poolSize;
    status = vkCreateDescriptorPool(g_device, &descriptorPoolInfo, nullptr,
                                    &g_videoDescriptorPool);
    if (status != VK_SUCCESS) {
        error = "Pool de descritores falhou: " + std::to_string(status);
        return false;
    }
    VkDescriptorSetAllocateInfo setAllocate{};
    setAllocate.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAllocate.descriptorPool = g_videoDescriptorPool;
    setAllocate.descriptorSetCount = 1;
    setAllocate.pSetLayouts = &g_videoSetLayout;
    status = vkAllocateDescriptorSets(g_device, &setAllocate, &g_videoDescriptorSet);
    if (status != VK_SUCCESS) {
        error = "Alocação do descritor de textura falhou: " + std::to_string(status);
        return false;
    }
    VkDescriptorImageInfo imageDescriptor{};
    imageDescriptor.sampler = g_videoSampler;
    imageDescriptor.imageView = g_videoImageView;
    imageDescriptor.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet write{};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = g_videoDescriptorSet;
    write.dstBinding = 0;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &imageDescriptor;
    vkUpdateDescriptorSets(g_device, 1, &write, 0, nullptr);
    g_videoWidth = width;
    g_videoHeight = height;
    return true;
}

bool createGraphicsPipeline(std::string& error) {
    auto makeShader = [](const uint32_t* code, size_t size, VkShaderModule* module) {
        VkShaderModuleCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = size;
        info.pCode = code;
        return vkCreateShaderModule(g_device, &info, nullptr, module);
    };
    VkShaderModule vert = VK_NULL_HANDLE, frag = VK_NULL_HANDLE;
    VkResult status = makeShader(triangle_vertex_spv, sizeof(triangle_vertex_spv), &vert);
    if (status != VK_SUCCESS) { error = "Vertex shader falhou: " + std::to_string(status); return false; }
    status = makeShader(triangle_fragment_spv, sizeof(triangle_fragment_spv), &frag);
    if (status != VK_SUCCESS) { vkDestroyShaderModule(g_device, vert, nullptr); error = "Fragment shader falhou: " + std::to_string(status); return false; }
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vert; stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = frag; stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertex{}; vertex.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo assembly{}; assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{}; viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; viewport.viewportCount = 1; viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{}; raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; raster.polygonMode = VK_POLYGON_MODE_FILL; raster.cullMode = VK_CULL_MODE_NONE; raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo samples{}; samples.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; samples.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState attachment{}; attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{}; blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; blend.attachmentCount = 1; blend.pAttachments = &attachment;
    const VkDynamicState states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{}; dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; dynamic.dynamicStateCount = 2; dynamic.pDynamicStates = states;
    VkPipelineLayoutCreateInfo layout{};
    layout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layout.setLayoutCount = 1;
    layout.pSetLayouts = &g_videoSetLayout;
    status = vkCreatePipelineLayout(g_device, &layout, nullptr, &g_pipelineLayout);
    if (status == VK_SUCCESS) {
        VkGraphicsPipelineCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.stageCount = 2; info.pStages = stages; info.pVertexInputState = &vertex; info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewport; info.pRasterizationState = &raster; info.pMultisampleState = &samples;
        info.pColorBlendState = &blend; info.pDynamicState = &dynamic; info.layout = g_pipelineLayout; info.renderPass = g_renderPass;
        status = vkCreateGraphicsPipelines(g_device, VK_NULL_HANDLE, 1, &info, nullptr, &g_graphicsPipeline);
    }
    vkDestroyShaderModule(g_device, frag, nullptr); vkDestroyShaderModule(g_device, vert, nullptr);
    if (status != VK_SUCCESS) { error = "Pipeline gráfico falhou: " + std::to_string(status); return false; }
    return true;
}

bool createRenderResources(std::string& error) {
    VkAttachmentDescription attachment{};
    attachment.format = g_swapchainFormat;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorReference{};
    colorReference.attachment = 0;
    colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorReference;

    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo renderInfo{};
    renderInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderInfo.attachmentCount = 1;
    renderInfo.pAttachments = &attachment;
    renderInfo.subpassCount = 1;
    renderInfo.pSubpasses = &subpass;
    renderInfo.dependencyCount = 1;
    renderInfo.pDependencies = &dependency;
    VkResult status = vkCreateRenderPass(g_device, &renderInfo, nullptr, &g_renderPass);
    if (status != VK_SUCCESS) {
        error = "vkCreateRenderPass falhou: " + std::to_string(status);
        return false;
    }

    if (!createVideoTexture(error)) return false;
    if (!createGraphicsPipeline(error)) return false;

    for (VkImageView view : g_swapchainViews) {
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = g_renderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = &view;
        framebufferInfo.width = ANativeWindow_getWidth(g_window);
        framebufferInfo.height = ANativeWindow_getHeight(g_window);
        framebufferInfo.layers = 1;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        status = vkCreateFramebuffer(g_device, &framebufferInfo, nullptr, &framebuffer);
        if (status != VK_SUCCESS) {
            error = "vkCreateFramebuffer falhou: " + std::to_string(status);
            return false;
        }
        g_framebuffers.push_back(framebuffer);
    }

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = g_graphicsFamily;
    status = vkCreateCommandPool(g_device, &poolInfo, nullptr, &g_commandPool);
    if (status != VK_SUCCESS) {
        error = "vkCreateCommandPool falhou: " + std::to_string(status);
        return false;
    }
    g_commandBuffers.resize(g_swapchainImages.size());
    VkCommandBufferAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocateInfo.commandPool = g_commandPool;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = static_cast<uint32_t>(g_commandBuffers.size());
    status = vkAllocateCommandBuffers(g_device, &allocateInfo, g_commandBuffers.data());
    if (status != VK_SUCCESS) {
        error = "vkAllocateCommandBuffers falhou: " + std::to_string(status);
        return false;
    }

    for (size_t i = 0; i < g_commandBuffers.size(); ++i) {
        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
        status = vkBeginCommandBuffer(g_commandBuffers[i], &beginInfo);
        if (status != VK_SUCCESS) {
            error = "vkBeginCommandBuffer falhou: " + std::to_string(status);
            return false;
        }
        VkClearValue clear{};
        clear.color.float32[0] = 0.035f;
        clear.color.float32[1] = 0.10f;
        clear.color.float32[2] = 0.22f;
        clear.color.float32[3] = 1.0f;
        VkRenderPassBeginInfo passInfo{};
        passInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        passInfo.renderPass = g_renderPass;
        passInfo.framebuffer = g_framebuffers[i];
        passInfo.renderArea.extent.width = ANativeWindow_getWidth(g_window);
        passInfo.renderArea.extent.height = ANativeWindow_getHeight(g_window);
        passInfo.clearValueCount = 1;
        passInfo.pClearValues = &clear;
        vkCmdBeginRenderPass(g_commandBuffers[i], &passInfo, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport vp{}; vp.width = static_cast<float>(ANativeWindow_getWidth(g_window)); vp.height = static_cast<float>(ANativeWindow_getHeight(g_window)); vp.maxDepth = 1.0f;
        VkRect2D sc{}; sc.extent.width = static_cast<uint32_t>(ANativeWindow_getWidth(g_window)); sc.extent.height = static_cast<uint32_t>(ANativeWindow_getHeight(g_window));
        vkCmdSetViewport(g_commandBuffers[i], 0, 1, &vp); vkCmdSetScissor(g_commandBuffers[i], 0, 1, &sc);
        vkCmdBindPipeline(g_commandBuffers[i], VK_PIPELINE_BIND_POINT_GRAPHICS, g_graphicsPipeline);
        vkCmdBindDescriptorSets(g_commandBuffers[i], VK_PIPELINE_BIND_POINT_GRAPHICS,
            g_pipelineLayout, 0, 1, &g_videoDescriptorSet, 0, nullptr);
        vkCmdDraw(g_commandBuffers[i], 6, 1, 0, 0);
        vkCmdEndRenderPass(g_commandBuffers[i]);
        status = vkEndCommandBuffer(g_commandBuffers[i]);
        if (status != VK_SUCCESS) {
            error = "vkEndCommandBuffer falhou: " + std::to_string(status);
            return false;
        }
    }

    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    status = vkCreateSemaphore(g_device, &semaphoreInfo, nullptr, &g_imageAvailable);
    if (status == VK_SUCCESS)
        status = vkCreateSemaphore(g_device, &semaphoreInfo, nullptr, &g_renderFinished);
    if (status != VK_SUCCESS) {
        error = "Criação de semáforo Vulkan falhou: " + std::to_string(status);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    status = vkCreateFence(g_device, &fenceInfo, nullptr, &g_frameFence);
    if (status != VK_SUCCESS) {
        error = "vkCreateFence falhou: " + std::to_string(status);
        return false;
    }
    return true;
}

bool presentTestFrame(std::string& error) {
    VkResult status = vkWaitForFences(g_device, 1, &g_frameFence, VK_TRUE, UINT64_MAX);
    if (status != VK_SUCCESS) {
        error = "vkWaitForFences falhou: " + std::to_string(status);
        return false;
    }
    uint32_t imageIndex = 0;
    status = vkAcquireNextImageKHR(g_device, g_swapchain, UINT64_MAX,
                                   g_imageAvailable, VK_NULL_HANDLE, &imageIndex);
    if (status == VK_ERROR_OUT_OF_DATE_KHR) {
        g_rendererFaulted = true;
        error = "Swapchain Vulkan desatualizada (VK_ERROR_OUT_OF_DATE_KHR); renderer pausado até reinicialização da superfície Android.";
        return false;
    }
    if (status != VK_SUCCESS && status != VK_SUBOPTIMAL_KHR) {
        g_rendererFaulted = true;
        error = "vkAcquireNextImageKHR falhou: " + std::to_string(status) +
            "; renderer pausado até reinicialização Vulkan.";
        return false;
    }
    const bool acquireSuboptimal = status == VK_SUBOPTIMAL_KHR;
    if (imageIndex >= g_commandBuffers.size()) {
        g_rendererFaulted = true;
        error = "Índice de imagem Vulkan fora do intervalo; renderer pausado até reinicialização.";
        return false;
    }
    status = vkResetFences(g_device, 1, &g_frameFence);
    if (status != VK_SUCCESS) {
        // Acquire already signaled imageAvailable. Replace it after idling so
        // a failed fence reset cannot leave a signaled semaphore for next frame.
        const VkResult idleStatus = vkDeviceWaitIdle(g_device);
        if (g_frameFence != VK_NULL_HANDLE) {
            vkDestroyFence(g_device, g_frameFence, nullptr);
            g_frameFence = VK_NULL_HANDLE;
        }
        if (g_imageAvailable != VK_NULL_HANDLE) {
            vkDestroySemaphore(g_device, g_imageAvailable, nullptr);
            g_imageAvailable = VK_NULL_HANDLE;
        }
        VkFenceCreateInfo recoveryFenceInfo{};
        recoveryFenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        recoveryFenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        const VkResult fenceStatus = vkCreateFence(
            g_device, &recoveryFenceInfo, nullptr, &g_frameFence);
        VkSemaphoreCreateInfo recoverySemaphoreInfo{};
        recoverySemaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        const VkResult semaphoreStatus = vkCreateSemaphore(
            g_device, &recoverySemaphoreInfo, nullptr, &g_imageAvailable);
        error = "vkResetFences falhou: " + std::to_string(status);
        if (idleStatus != VK_SUCCESS)
            error += "; vkDeviceWaitIdle falhou: " + std::to_string(idleStatus);
        if (fenceStatus != VK_SUCCESS) {
            g_frameFence = VK_NULL_HANDLE;
            error += "; recuperação da fence falhou: " + std::to_string(fenceStatus);
        }
        if (semaphoreStatus != VK_SUCCESS) {
            g_imageAvailable = VK_NULL_HANDLE;
            error += "; recuperação do semáforo falhou: " + std::to_string(semaphoreStatus);
        }
        return false;
    }
    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &g_imageAvailable;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g_commandBuffers[imageIndex];
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &g_renderFinished;
    status = vkQueueSubmit(g_graphicsQueue, 1, &submit, g_frameFence);
    if (status != VK_SUCCESS) {
        // Acquire signaled imageAvailable, but a failed submit did not consume it.
        // Wait for the device before replacing all frame-sync objects so neither
        // semaphore can be reused in an unknown signaled state.
        const VkResult idleStatus = vkDeviceWaitIdle(g_device);
        error = "vkQueueSubmit falhou: " + std::to_string(status);
        if (idleStatus != VK_SUCCESS) {
            g_rendererFaulted = true;
            error += "; vkDeviceWaitIdle falhou durante recuperação: " +
                std::to_string(idleStatus) +
                "; renderer bloqueado até reinicialização Vulkan.";
            return false;
        }

        if (g_frameFence != VK_NULL_HANDLE)
            vkDestroyFence(g_device, g_frameFence, nullptr);
        if (g_imageAvailable != VK_NULL_HANDLE)
            vkDestroySemaphore(g_device, g_imageAvailable, nullptr);
        if (g_renderFinished != VK_NULL_HANDLE)
            vkDestroySemaphore(g_device, g_renderFinished, nullptr);
        g_frameFence = VK_NULL_HANDLE;
        g_imageAvailable = VK_NULL_HANDLE;
        g_renderFinished = VK_NULL_HANDLE;

        VkFenceCreateInfo recoveryFenceInfo{};
        recoveryFenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        recoveryFenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        const VkResult fenceStatus =
            vkCreateFence(g_device, &recoveryFenceInfo, nullptr, &g_frameFence);
        VkSemaphoreCreateInfo recoverySemaphoreInfo{};
        recoverySemaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        const VkResult imageSemaphoreStatus =
            vkCreateSemaphore(g_device, &recoverySemaphoreInfo, nullptr, &g_imageAvailable);
        const VkResult renderSemaphoreStatus =
            vkCreateSemaphore(g_device, &recoverySemaphoreInfo, nullptr, &g_renderFinished);

        if (fenceStatus != VK_SUCCESS || imageSemaphoreStatus != VK_SUCCESS ||
            renderSemaphoreStatus != VK_SUCCESS) {
            g_rendererFaulted = true;
            error += "; falha ao recriar sincronização (fence=" +
                std::to_string(fenceStatus) + ", imageAvailable=" +
                std::to_string(imageSemaphoreStatus) + ", renderFinished=" +
                std::to_string(renderSemaphoreStatus) +
                "); renderer bloqueado até reinicialização Vulkan.";
        } else {
            error += "; sincronização recriada após aguardar o dispositivo.";
        }
        return false;
    }
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &g_renderFinished;
    present.swapchainCount = 1;
    present.pSwapchains = &g_swapchain;
    present.pImageIndices = &imageIndex;
    status = vkQueuePresentKHR(g_presentQueue, &present);
    if (status == VK_ERROR_OUT_OF_DATE_KHR) {
        g_rendererFaulted = true;
        error = "Apresentação Vulkan desatualizada (VK_ERROR_OUT_OF_DATE_KHR); renderer pausado até reinicialização da superfície Android.";
        return false;
    }
    if (status != VK_SUCCESS && status != VK_SUBOPTIMAL_KHR) {
        g_rendererFaulted = true;
        error = "vkQueuePresentKHR falhou: " + std::to_string(status) +
            "; renderer pausado até reinicialização Vulkan.";
        return false;
    }
    const bool presentSuboptimal = status == VK_SUBOPTIMAL_KHR;
    status = vkQueueWaitIdle(g_presentQueue);
    if (status != VK_SUCCESS) {
        g_rendererFaulted = true;
        error = "vkQueueWaitIdle após apresentação falhou: " + std::to_string(status) +
            "; renderer pausado até reinicialização Vulkan.";
        return false;
    }
    error = (acquireSuboptimal || presentSuboptimal)
        ? "Quadro apresentado, mas a swapchain ficou subótima; a superfície será recriada se o Android informar mudança de tamanho."
        : "textura RGBA de teste enviada por staging buffer e exibida pelo sampler Vulkan; ROM ainda não conectada.";
    return true;
}

bool createSwapchain(std::string& details) {
    VkSurfaceCapabilitiesKHR capabilities{};
    VkResult status = pGetSurfaceCapabilities(g_physicalDevice, g_surface, &capabilities);
    if (status != VK_SUCCESS) {
        details = "Consulta de capacidades da superfície falhou: " + std::to_string(status);
        return false;
    }
    uint32_t formatCount = 0;
    status = pGetSurfaceFormats(g_physicalDevice, g_surface, &formatCount, nullptr);
    if (status != VK_SUCCESS || formatCount == 0) {
        details = "A superfície não oferece formatos de imagem.";
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    pGetSurfaceFormats(g_physicalDevice, g_surface, &formatCount, formats.data());
    VkSurfaceFormatKHR chosen = formats.front();
    for (const auto& format : formats) {
        if ((format.format == VK_FORMAT_R8G8B8A8_UNORM ||
             format.format == VK_FORMAT_B8G8R8A8_UNORM) &&
            format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = format;
            break;
        }
    }

    VkExtent2D extent = capabilities.currentExtent;
    if (extent.width == UINT32_MAX) {
        const uint32_t width = static_cast<uint32_t>(ANativeWindow_getWidth(g_window));
        const uint32_t height = static_cast<uint32_t>(ANativeWindow_getHeight(g_window));
        extent.width = std::clamp(width, capabilities.minImageExtent.width,
                                  capabilities.maxImageExtent.width);
        extent.height = std::clamp(height, capabilities.minImageExtent.height,
                                   capabilities.maxImageExtent.height);
    }
    if (extent.width == 0 || extent.height == 0) {
        details = "Tamanho da superfície Vulkan inválido.";
        return false;
    }
    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0)
        imageCount = std::min(imageCount, capabilities.maxImageCount);
    VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if ((capabilities.supportedCompositeAlpha & composite) == 0) {
        const VkCompositeAlphaFlagBitsKHR choices[] = {
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };
        for (VkCompositeAlphaFlagBitsKHR choice : choices) {
            if ((capabilities.supportedCompositeAlpha & choice) != 0) {
                composite = choice;
                break;
            }
        }
    }
    uint32_t modeCount = 0;
    pGetPresentModes(g_physicalDevice, g_surface, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    pGetPresentModes(g_physicalDevice, g_surface, &modeCount, modes.data());
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    VkSwapchainCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = g_surface;
    info.minImageCount = imageCount;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
        details = "A superfície não suporta imagens como color attachment.";
        return false;
    }
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    if (g_graphicsFamily != g_presentFamily) {
        const uint32_t families[] = {g_graphicsFamily, g_presentFamily};
        info.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        info.queueFamilyIndexCount = 2;
        info.pQueueFamilyIndices = families;
    } else {
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    }
    info.preTransform = capabilities.currentTransform;
    info.compositeAlpha = composite;
    info.presentMode = presentMode;
    info.clipped = VK_TRUE;
    status = pCreateSwapchain(g_device, &info, nullptr, &g_swapchain);
    if (status != VK_SUCCESS) {
        details = "vkCreateSwapchainKHR falhou: " + std::to_string(status);
        return false;
    }
    uint32_t actualCount = 0;
    status = pGetSwapchainImages(g_device, g_swapchain, &actualCount, nullptr);
    if (status != VK_SUCCESS || actualCount == 0) {
        details = "Não foi possível obter imagens da swapchain.";
        return false;
    }
    g_swapchainImages.resize(actualCount);
    status = pGetSwapchainImages(g_device, g_swapchain, &actualCount, g_swapchainImages.data());
    if (status != VK_SUCCESS) {
        details = "Falha ao carregar imagens da swapchain.";
        return false;
    }
    auto createView = reinterpret_cast<PFN_vkCreateImageView>(
        vkGetDeviceProcAddr(g_device, "vkCreateImageView"));
    if (!createView) {
        details = "vkCreateImageView não está disponível.";
        return false;
    }
    for (VkImage image : g_swapchainImages) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = chosen.format;
        viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        VkImageView view = VK_NULL_HANDLE;
        status = createView(g_device, &viewInfo, nullptr, &view);
        if (status != VK_SUCCESS) {
            details = "Criação de imagem da swapchain falhou: " + std::to_string(status);
            return false;
        }
        g_swapchainViews.push_back(view);
    }
    g_swapchainFormat = chosen.format;
    details = std::to_string(extent.width) + "x" + std::to_string(extent.height) +
        ", " + std::to_string(g_swapchainImages.size()) + " imagens";
    if (!createRenderResources(details)) return false;
    if (!presentTestFrame(details)) return false;
    return true;
}

jstring result(JNIEnv* env, const std::string& message) {
    return env->NewStringUTF(message.c_str());
}
} // namespace

// Entry point for the N64 core: copies the callback frame into Vulkan-owned
// staging memory before the producer is allowed to reuse its pixel buffer.
extern "C" bool n64VulkanSubmitVideoFrame(const N64VideoFrame* frame) {
    if (frame == nullptr) return false;
    std::lock_guard<std::mutex> lock(g_mutex);

    // The core may call this adapter before surface creation or during teardown.
    // Reject the frame unless every object needed by upload and presentation is live.
    if (g_rendererFaulted ||
        g_device == VK_NULL_HANDLE ||
        g_physicalDevice == VK_NULL_HANDLE ||
        g_graphicsQueue == VK_NULL_HANDLE ||
        g_presentQueue == VK_NULL_HANDLE ||
        g_swapchain == VK_NULL_HANDLE ||
        g_videoImage == VK_NULL_HANDLE ||
        g_videoImageView == VK_NULL_HANDLE ||
        g_videoDescriptorSet == VK_NULL_HANDLE ||
        g_graphicsPipeline == VK_NULL_HANDLE ||
        g_imageAvailable == VK_NULL_HANDLE ||
        g_renderFinished == VK_NULL_HANDLE ||
        g_frameFence == VK_NULL_HANDLE) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "Quadro Vulkan rejeitado: renderer ainda não está inicializado.");
        return false;
    }

    std::string error;
    const bool uploaded = uploadVideoFrameLocked(*frame, error);
    if (!uploaded) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", error.c_str());
        return false;
    }
    if (!presentTestFrame(error)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Quadro enviado, mas apresentação Vulkan falhou: %s", error.c_str());
        return false;
    }
    return true;
}

// Adapter can be registered directly as an N64VideoFrameCallback.
// user_data is reserved for future per-core/per-instance renderer contexts.
extern "C" void n64VulkanVideoFrameCallback(
    const N64VideoFrame* frame, void* /* user_data */) {
    if (!n64VulkanSubmitVideoFrame(frame)) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "Quadro do núcleo N64 não foi enviado ao renderer Vulkan.");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_VulkanNative_nativeInitialize(
    JNIEnv* env, jobject /* thiz */, jobject surface) {
    std::lock_guard<std::mutex> lock(g_mutex);
    shutdownLocked();
    if (surface == nullptr) return result(env, "Surface Android nula.");
    g_window = ANativeWindow_fromSurface(env, surface);
    if (g_window == nullptr) return result(env, "ANativeWindow_fromSurface falhou.");

    if (!hasInstanceExtension(VK_KHR_SURFACE_EXTENSION_NAME) ||
        !hasInstanceExtension(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
        shutdownLocked();
        return result(env, "Driver não oferece extensões de superfície Vulkan Android.");
    }
    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "N64 Android";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "N64 Vulkan Backend";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    instanceInfo.enabledExtensionCount = 2;
    instanceInfo.ppEnabledExtensionNames = extensions;
    VkResult status = vkCreateInstance(&instanceInfo, nullptr, &g_instance);
    if (status != VK_SUCCESS) {
        shutdownLocked();
        return result(env, "vkCreateInstance falhou: " + std::to_string(status));
    }
    auto createSurface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
        vkGetInstanceProcAddr(g_instance, "vkCreateAndroidSurfaceKHR"));
    if (!createSurface || !loadSurfaceFunctions()) {
        shutdownLocked();
        return result(env, "Funções de superfície Vulkan Android indisponíveis.");
    }
    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = g_window;
    status = createSurface(g_instance, &surfaceInfo, nullptr, &g_surface);
    if (status != VK_SUCCESS) {
        shutdownLocked();
        return result(env, "vkCreateAndroidSurfaceKHR falhou: " + std::to_string(status));
    }

    std::string gpuName;
    std::string error;
    if (!selectPhysicalDevice(gpuName)) {
        const std::string message = gpuName;
        shutdownLocked();
        return result(env, message);
    }
    if (!createLogicalDevice(error) || !createSwapchain(error)) {
        const std::string message = error.empty()
            ? "Falha ao inicializar dispositivo Vulkan." : error;
        shutdownLocked();
        return result(env, message);
    }
    const std::string message = "Superfície Vulkan Android criada. GPU: " + gpuName +
        ". Teste de apresentação: " + error;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
    return result(env, message);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jsr_n64android_VulkanNative_nativeShutdown(
    JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    shutdownLocked();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Dispositivo Vulkan e swapchain encerrados.");
}
