#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <new>

namespace {

constexpr jint kApiVersion = 3;
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

bool valid_address(jint address, std::size_t width, std::size_t alignment) noexcept {
    if (address < 0 || width == 0U || alignment == 0U) return false;
    const auto offset = static_cast<std::size_t>(address);
    return (offset % alignment) == 0U && offset <= kRdramSize - width;
}

std::uint64_t read_be(const EngineHandle& engine, std::size_t offset,
                      std::size_t width) noexcept {
    std::uint64_t value = 0;
    for (std::size_t i = 0; i < width; ++i) {
        value = (value << 8U) | engine.rdram[offset + i];
    }
    return value;
}

void write_be(EngineHandle& engine, std::size_t offset, std::size_t width,
              std::uint64_t value) noexcept {
    for (std::size_t i = 0; i < width; ++i) {
        const auto shift = static_cast<unsigned>((width - 1U - i) * 8U);
        engine.rdram[offset + i] = static_cast<std::uint8_t>(value >> shift);
    }
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

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeReadRdramByte(
        JNIEnv*, jobject, jlong handle, jint address) {
    const auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 1U, 1U)) return 0;
    return static_cast<jint>(read_be(*engine, static_cast<std::size_t>(address), 1U));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeWriteRdramByte(
        JNIEnv*, jobject, jlong handle, jint address, jint value) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 1U, 1U) ||
        value < 0 || value > 0xFF) return JNI_FALSE;
    write_be(*engine, static_cast<std::size_t>(address), 1U,
             static_cast<std::uint32_t>(value));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeReadRdramHalf(
        JNIEnv*, jobject, jlong handle, jint address) {
    const auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 2U, 2U)) return 0;
    return static_cast<jint>(read_be(*engine, static_cast<std::size_t>(address), 2U));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeWriteRdramHalf(
        JNIEnv*, jobject, jlong handle, jint address, jint value) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 2U, 2U) ||
        value < 0 || value > 0xFFFF) return JNI_FALSE;
    write_be(*engine, static_cast<std::size_t>(address), 2U,
             static_cast<std::uint32_t>(value));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeReadRdramWord(
        JNIEnv*, jobject, jlong handle, jint address) {
    const auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 4U, 4U)) return 0;
    return static_cast<jint>(static_cast<std::uint32_t>(
            read_be(*engine, static_cast<std::size_t>(address), 4U)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeWriteRdramWord(
        JNIEnv*, jobject, jlong handle, jint address, jint value) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 4U, 4U)) return JNI_FALSE;
    write_be(*engine, static_cast<std::size_t>(address), 4U,
             static_cast<std::uint32_t>(value));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeReadRdramDouble(
        JNIEnv*, jobject, jlong handle, jint address) {
    const auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 8U, 8U)) return 0;
    return static_cast<jlong>(read_be(*engine, static_cast<std::size_t>(address), 8U));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jsr_n64android_N64NativeEngine_nativeWriteRdramDouble(
        JNIEnv*, jobject, jlong handle, jint address, jlong value) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || !valid_address(address, 8U, 8U)) return JNI_FALSE;
    write_be(*engine, static_cast<std::size_t>(address), 8U,
             static_cast<std::uint64_t>(value));
    return JNI_TRUE;
}
