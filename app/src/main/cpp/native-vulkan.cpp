#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* TAG = "N64AndroidVulkan";
std::mutex g_mutex;
VkInstance g_instance = VK_NULL_HANDLE;
VkSurfaceKHR g_surface = VK_NULL_HANDLE;
ANativeWindow* g_window = nullptr;

void shutdownLocked() {
    if (g_instance != VK_NULL_HANDLE && g_surface != VK_NULL_HANDLE) {
        auto destroySurface = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
            vkGetInstanceProcAddr(g_instance, "vkDestroySurfaceKHR"));
        if (destroySurface) destroySurface(g_instance, g_surface, nullptr);
    }
    g_surface = VK_NULL_HANDLE;
    if (g_instance != VK_NULL_HANDLE) vkDestroyInstance(g_instance, nullptr);
    g_instance = VK_NULL_HANDLE;
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
        if (std::string(extension.extensionName) == wanted) return true;
    }
    return false;
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
        return result(env, "Driver não oferece VK_KHR_surface/VK_KHR_android_surface.");
    }

    const char* extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
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
    if (createSurface == nullptr) {
        shutdownLocked();
        return result(env, "vkCreateAndroidSurfaceKHR não foi encontrado.");
    }

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{
        VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.window = g_window;
    status = createSurface(g_instance, &surfaceInfo, nullptr, &g_surface);
    if (status != VK_SUCCESS) {
        shutdownLocked();
        return result(env, "vkCreateAndroidSurfaceKHR falhou: " + std::to_string(status));
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "VkInstance e VkSurfaceKHR criados. Nenhum swapchain/renderizador foi iniciado ainda.");
    return result(env, "Superfície Vulkan Android criada. Próxima etapa: GPU física e swapchain.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_jsr_n64android_VulkanNative_nativeShutdown(
    JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    shutdownLocked();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Superfície Vulkan encerrada.");
}
