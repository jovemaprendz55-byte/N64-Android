# N64-Android

Aplicativo Android para desenvolver, de forma incremental, um emulador de Nintendo 64.

## Estado atual

- O app permite escolher um arquivo no seletor do Android e identificar o formato do cabeçalho da ROM (Z64, V64 ou N64).
- Foi adicionada uma primeira base de interpretador MIPS III/R4300 em Kotlin, com registradores, memória big-endian simplificada e um subconjunto de instruções.
- Testes unitários cobrem aritmética imediata, registrador zero, acesso à memória e delay slot de desvio.

**Importante:** ainda não executa jogos comerciais de Nintendo 64. O núcleo é parcial e ainda faltam muitas instruções, exceções, TLB, CP0/FPU, mapeamento de memória do N64, RSP/RDP, áudio, vídeo e controles.

## Tecnologias

- Kotlin e Android SDK
- Gradle
- Testes unitários com JUnit
- GitHub Actions para compilar o APK de depuração e executar os testes

## Compilar

Abra o projeto no Android Studio com JDK 17 e SDK Android 36, ou execute em um ambiente com Android SDK instalado:

```bash
gradle assembleDebug testDebugUnitTest
```

O APK de depuração será gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximas etapas

1. Expandir o conjunto de instruções e adicionar testes de conformidade.
2. Implementar exceções, CP0, TLB e um modelo de memória fiel ao N64.
3. Normalizar ROMs Z64/V64/N64 e integrar a execução do código de inicialização.
4. Desenvolver RSP/RDP, gráficos, áudio, controles e salvamento.

Use apenas ROMs que você tem direito de utilizar. Este projeto não inclui jogos nem BIOS proprietárias.
