#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include "triangle_shaders.h"

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
        if (g_pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(g_device, g_pipelineLayout, nullptr);
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
            if (!createGraphicsPipeline(error)) return false;

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
    VkPipelineLayoutCreateInfo layout{}; layout.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
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
        vkCmdDraw(g_commandBuffers[i], 3, 1, 0, 0);
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
    if (status != VK_SUCCESS && status != VK_SUBOPTIMAL_KHR) {
        error = "vkAcquireNextImageKHR falhou: " + std::to_string(status);
        return false;
    }
    if (imageIndex >= g_commandBuffers.size()) {
        error = "Índice de imagem Vulkan fora do intervalo.";
        return false;
    }
    vkResetFences(g_device, 1, &g_frameFence);
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
        error = "vkQueueSubmit falhou: " + std::to_string(status);
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
    if (status != VK_SUCCESS && status != VK_SUBOPTIMAL_KHR) {
        error = "vkQueuePresentKHR falhou: " + std::to_string(status);
        return false;
    }
    vkQueueWaitIdle(g_presentQueue);
    error = "triângulo colorido renderizado e apresentado pela Vulkan."
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
