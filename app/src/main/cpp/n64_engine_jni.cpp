#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <new>

namespace {

constexpr jint kApiVersion = 2;
constexpr jint kStateCreated = 1;
constexpr std::size_t kRdramSize = 8U * 1024U * 1024U;

struct EngineHandle final {
    std::uint32_t api_version = static_cast<std::uint32_t>(kApiVersion);
    std::uint32_t state = static_cast<std::uint32_t>(kStateCreated);
    std::array<std::uint8_t, kRdramSize> rdram{};
};

EngineHandle* from_handle(jlong handle) noexcept {
    return reinterpret_cast<EngineHandle*>(static_cast<std::uintptr_t>(handle));
}

bool valid_word_address(jint address) noexcept {
    return address >= 0 &&
           (static_cast<std::size_t>(address) % 4U) == 0U &&
           static_cast<std::size_t>(address) <= kRdramSize - 4U;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeWriteRdramWord(
        JNIEnv*, jobject, jlong handle, jint address, jint value) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_word_address(address)) {
        return JNI_FALSE;
    }

    const auto offset = static_cast<std::size_t>(address);
    const auto word = static_cast<std::uint32_t>(value);
    engine->rdram[offset] = static_cast<std::uint8_t>(word >> 24U);
    engine->rdram[offset + 1U] = static_cast<std::uint8_t>(word >> 16U);
    engine->rdram[offset + 2U] = static_cast<std::uint8_t>(word >> 8U);
    engine->rdram[offset + 3U] = static_cast<std::uint8_t>(word);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeReadRdramWord(
        JNIEnv*, jobject, jlong handle, jint address) {
    const auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_word_address(address)) {
        return 0;
    }

    const auto offset = static_cast<std::size_t>(address);
    const std::uint32_t word =
            (static_cast<std::uint32_t>(engine->rdram[offset]) << 24U) |
            (static_cast<std::uint32_t>(engine->rdram[offset + 1U]) << 16U) |
            (static_cast<std::uint32_t>(engine->rdram[offset + 2U]) << 8U) |
            static_cast<std::uint32_t>(engine->rdram[offset + 3U]);
    return static_cast<jint>(word);
}
