# Android + NDK + JNI — estado do build

## Estado verificado em 2026-11-08

O APK ARM64 agora inclui quatro componentes nativos separados:
- `libn64bridge.so`: frontend JNI de diagnóstico;
- `libSDL2.so`: SDL2 Android compilada pelo NDK;
- `libmupen64plus.so`: Mupen64Plus Core compilado para ARM64;
- `libmupen64plus-video-GLideN64.so`: plugin gráfico GLideN64 compilado para Android/EGL;
- também permanece `libmupen64plus-video-android.so`, o plugin inicial de diagnóstico ABI.

### CI e artefato

- Branch: `main`
- Commit validado: `2542d80a348db6e04918cb2c5427cc414d5e0d47`
- Workflow: [Android Core isolated diagnostic build](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42504515807)
- Resultado: **success** (todas as etapas, incluindo verificação do APK e upload).
- Artefato: `n64-android-core-isolated-diagnostic`
- Artifact ID: `13627215848`
- Tamanho: 3.067.855 bytes
- SHA-256: `0570e5ac834c6acf216323c7f963f7e34fcf985d4975288a9d82c01924584be8`
- Download do artefato: [GitHub Actions artifact](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42504515807)

### O que o CI comprovou

1. Compilou o Core upstream fixado em `a9ca8ec0b7decd17390c76c6341cf423d5d79321` para `arm64-v8a`.
2. Compilou SDL2 upstream fixada em `5d249570393f7a37e037abf22cd6012a4cc56a71`.
3. Compilou GLideN64 upstream fixado em `a04081ad6a966a9413bea63a55f502e0dc98bb18`, com o patch Android GLES documentado em `patches/gliden64/`.
4. Verificou no APK os símbolos ABI GFX exigidos, incluindo `PluginGetVersion`, `PluginStartup`, `InitiateGFX`, `ProcessDList` e `ProcessRDPList`.
5. Verificou `CoreStartup` e `CoreShutdown` no Core.
6. Confirmou que a bridge JNI não possui dependência de inicialização em SDL2/Core; o Core depende de `libSDL2.so`.
7. O plugin GLideN64 depende somente de bibliotecas do sistema Android observadas no ELF: EGL, log, math, dl e libc.

Isso comprova compilação, arquitetura, exports e empacotamento. **Não comprova ainda que GLideN64 inicialize/renderize no aparelho ou que algum jogo execute.**

## Falha anterior e correção

A execução `42489433984` falhou em `CRC32_ARMV8.cpp`: os intrínsecos `__crc32d/__crc32w/__crc32h/__crc32b` exigiam a feature ARM `crc`, que não estava habilitada no alvo Android genérico. O build Android seleciona o caminho NEON; o workflow passou a configurar `CRC_ARMV8=OFF`, evitando instruções CRC não garantidas. As execuções posteriores compilaram o plugin até 100%.

O patch de perfil GLES também é aplicado por uma substituição estrita do bloco upstream: o bloco antigo precisa existir exatamente uma vez, e `git diff --check` valida o resultado. Isso evita aplicar silenciosamente a adaptação sobre uma versão upstream diferente.

## Configuração fixada

- Android Gradle Plugin: 8.7.3
- Kotlin Android plugin: 2.0.21
- Gradle: 8.9 (CI)
- JDK: 17
- compileSdk / targetSdk: 35
- minSdk: 23
- ABI: `arm64-v8a`
- NDK: 27.0.12077973
- CMake: 3.22.1

## Próximo passo obrigatório no Poco C85

O botão **Iniciar integração gráfica** agora tenta carregar o GLideN64 real e executar `PluginStartup`, após inicializar o Core e registrar a extensão de vídeo Android. Instalar o artefato acima e conferir o resultado dessa etapa no aparelho.

Ainda ficam bloqueados:
- `InitiateGFX` e `CoreAttachPlugin`;
- `M64CMD_ROM_OPEN` e `M64CMD_EXECUTE`;
- qualquer alegação de renderização ou jogo executando.

O bloqueio técnico seguinte é a propriedade do contexto EGL: o contexto atual pertence à thread de renderização do `GLSurfaceView`, enquanto o GLideN64 Android tem modo threaded habilitado por padrão. Precisamos definir e testar a estratégia de thread/contexto antes de iniciar o plugin gráfico. Depois disso, avançar para abertura de ROM e ciclo de vida seguro.
