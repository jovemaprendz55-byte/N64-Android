# Android + NDK + JNI — primeira estrutura

## Estado

Esta etapa cria um aplicativo Android mínimo e uma biblioteca nativa JNI. Ela **não contém nem executa ainda o Mupen64Plus Core**.

O aplicativo exibe uma mensagem nativa para provar, quando o CI compilar e o APK for instalado, que a biblioteca JNI foi carregada. A mensagem não representa emulação.

## Estrutura

- `settings.gradle.kts`: projeto Gradle e módulo `:app`.
- `app/build.gradle.kts`: Android Gradle Plugin, Kotlin, SDK e CMake.
- `app/src/main/AndroidManifest.xml`: Activity inicial.
- `MainActivity.kt`: tela mínima e chamada JNI.
- `app/src/main/cpp/CMakeLists.txt`: build nativo.
- `native-lib.cpp`: função JNI de diagnóstico.
- `.github/workflows/android.yml`: build automático do APK de depuração.

## Configuração fixada

- Android Gradle Plugin: 8.7.3
- Kotlin Android plugin: 2.0.21
- Gradle: 8.9 (CI)
- JDK: 17
- compileSdk / targetSdk: 35
- minSdk: 23
- ABI nativa inicial: `arm64-v8a` (alvo do primeiro aparelho; outras ABIs serão adicionadas quando houver necessidade e testes).
- NDK: 27.0.12077973
- CMake: 3.22.1

## Como validar

O workflow GitHub Actions executa `gradle assembleDebug` e publica o APK como artefato se a compilação terminar com sucesso. A validação seguinte é instalar o APK no aparelho e confirmar que a tela informa que a bridge JNI foi carregada.

## Próxima integração

Antes de incorporar o Core, mapear e resolver dependências SDL, sistema operacional, carregamento de bibliotecas, threads e armazenamento. O código upstream deve continuar fixado no commit registrado em `CORE-ANALYSIS.md`; cada alteração será rastreável e manterá os avisos/licenças originais.


## Verificação CI mais recente — 2026-11-08

- Commit: `c3eb7bb5169fe58447351b23bdc19aa4c9f703d1`
- Workflow: [Android build #31](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42432094799)
- Resultado: **success**.
- O CMake agora habilita C e C++, compila o helper oficial `src/asm_defines/asm_defines.c` do Core fixado, gera os headers GAS/NASM ARM64 e conclui `assembleDebug`.
- Artefato: `n64-android-debug` (812.507 bytes).

Esta verificação confirma a geração dos offsets dentro do build Android e a produção do APK. **Ela ainda não compila as fontes do Core**: `libn64bridge.so` continua sendo apenas a bridge JNI de diagnóstico.

## Próxima etapa técnica

Antes de criar o target `libmupen64plus.so`, fechar a seleção e o pin das dependências Android (SDL2, libpng e zlib), decidir explicitamente a política Vulkan/GLES e separar o backend de vídeo do ciclo de vida SDL. Depois disso, o Core será adicionado como target CMake independente, mantendo o APK de diagnóstico funcional enquanto o novo target é validado.
