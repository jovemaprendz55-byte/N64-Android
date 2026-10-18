# N64-Android

Aplicativo Android para desenvolver, de forma incremental, um emulador de Nintendo 64.

## Estado atual

**Primeiro esqueleto do app:** permite escolher um arquivo pelo seletor do Android e identificar o formato do cabeçalho da ROM (Z64, V64 ou N64). Ainda não executa jogos: o núcleo de emulação precisa ser implementado e testado.

## Tecnologias

- Kotlin e Android SDK
- Gradle
- Testes unitários para a identificação do cabeçalho
- GitHub Actions para compilar o APK de depuração e executar os testes

## Compilar

Abra o projeto no Android Studio com JDK 17 e SDK Android 36, ou execute em um ambiente com Android SDK instalado:

```bash
gradle assembleDebug testDebugUnitTest
```

O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximas etapas

1. Testar a compilação e corrigir problemas do esqueleto.
2. Testar leitura e normalização completa de ROMs.
3. Desenvolver o núcleo MIPS R4300, memória e exceções.
4. Adicionar RSP, RDP/gráficos, áudio, controles e salvamento.

Use apenas ROMs que você tem direito de utilizar. Este projeto não inclui jogos nem BIOS proprietárias.
