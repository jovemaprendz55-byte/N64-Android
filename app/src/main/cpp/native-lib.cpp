#include <jni.h>

#ifdef N64_HAS_SDL2
#include <SDL.h>
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
#ifdef N64_HAS_SDL2
    SDL_version version{};
    SDL_GetVersion(&version);
    const char* status =
        "Bridge JNI carregada com sucesso.\n"
        "SDL2 carregada: " SDL_VERSION "";
    char details[160];
    snprintf(details, sizeof(details),
             "%sVersao SDL2: %u.%u.%u\n"
             "Core Mupen64Plus ainda nao foi integrado.\n"
             "Nenhuma ROM esta sendo executada nesta etapa.",
             status, version.major, version.minor, version.patch);
    return env->NewStringUTF(details);
#else
    constexpr char kStatus[] =
        "Bridge JNI carregada com sucesso.\n"
        "SDL2 ainda nao foi vinculada neste build.\n"
        "Core Mupen64Plus ainda nao foi integrado.\n"
        "Nenhuma ROM esta sendo executada nesta etapa.";
    return env->NewStringUTF(kStatus);
#endif
}
