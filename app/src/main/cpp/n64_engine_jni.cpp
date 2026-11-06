#include <jni.h>

#include <cstdint>
#include <new>

namespace {

constexpr jint kApiVersion = 1;
constexpr jint kStateCreated = 1;

struct EngineHandle final {
    std::uint32_t api_version = static_cast<std::uint32_t>(kApiVersion);
    std::uint32_t state = static_cast<std::uint32_t>(kStateCreated);
};

EngineHandle* from_handle(jlong handle) noexcept {
    return reinterpret_cast<EngineHandle*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeCreate(JNIEnv*, jobject) {
    auto* engine = new (std::nothrow) EngineHandle();
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(engine));
}

extern "C" JNIEXPORT void JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeGetApiVersion(JNIEnv*, jobject, jlong handle) {
    const auto* engine = from_handle(handle);
    return engine == nullptr ? 0 : static_cast<jint>(engine->api_version);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeGetState(JNIEnv*, jobject, jlong handle) {
    const auto* engine = from_handle(handle);
    return engine == nullptr ? 0 : static_cast<jint>(engine->state);
}
