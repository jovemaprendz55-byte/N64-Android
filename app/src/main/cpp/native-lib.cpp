#include <jni.h>

#ifdef N64_HAS_SDL2
#include <SDL.h>
#include <cstdio>
#endif

#ifdef N64_HAS_MUPEN64PLUS
#define M64P_CORE_PROTOTYPES
#include "m64p_frontend.h"
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_jsr_n64android_MainActivity_nativeGetStatus(JNIEnv* env, jobject /* thiz */) {
#ifdef N64_HAS_SDL2
    SDL_version version{};
    SDL_GetVersion(&version);
    char details[320];
#ifdef N64_HAS_MUPEN64PLUS
    const bool coreApiAvailable =
        (&CoreStartup != nullptr) &&
        (&CoreDoCommand != nullptr) &&
        (&CoreShutdown != nullptr);
    std::snprintf(details, sizeof(details),
                  "Bridge JNI carregada com sucesso.\n"
                  "SDL2 carregada: %u.%u.%u\n"
                  "Core Mupen64Plus linkado: sim\n"
                  "API frontend exportada: %s\n"
                  "Core ainda nao inicializado; plugins e configuracao pendentes.\n"
                  "Nenhuma ROM esta sendo executada.",
                  version.major, version.minor, version.patch,
                  coreApiAvailable ? "sim" : "nao");
#else
    std::snprintf(details, sizeof(details),
                  "Bridge JNI carregada com sucesso.\n"
                  "SDL2 carregada: %u.%u.%u\n"
                  "Core Mupen64Plus nao esta linkado.\n"
                  "Nenhuma ROM esta sendo executada.",
                  version.major, version.minor, version.patch);
#endif
    return env->NewStringUTF(details);
#else
    constexpr char kStatus[] =
        "Bridge JNI carregada com sucesso.\n"
        "SDL2 ainda nao foi vinculada neste build.\n"
        "Nenhuma ROM esta sendo executada nesta etapa.";
    return env->NewStringUTF(kStatus);
#endif
}
