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
