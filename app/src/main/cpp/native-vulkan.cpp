#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

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
    if (!selectPhysicalDevice(gpuName) || !createLogicalDevice(error) ||
        !createSwapchain(error)) {
        const std::string message = !gpuName.empty() && error.empty()
            ? "Falha ao inicializar dispositivo Vulkan." : error;
        shutdownLocked();
        return result(env, message.empty() ? gpuName : message);
    }
    const std::string message = "Superfície Vulkan Android criada. GPU: " + gpuName +
        ". Swapchain: " + error + ". Ainda falta implementar comandos de desenho.";
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
