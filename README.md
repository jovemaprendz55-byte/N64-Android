# N64-Android

Aplicativo Android para desenvolver, de forma incremental, um emulador de Nintendo 64.

## Estado atual

- O app permite selecionar uma ROM pelo seletor de arquivos do Android.
- A validação verifica cabeçalho reconhecido, tamanho mínimo de 4 KiB, alinhamento de 4 bytes e limite de 64 MiB.
- Ao selecionar uma ROM válida, o app converte seus bytes para a ordem big-endian Z64 e mantém a ROM normalizada em memória.
- O app mostra título, formato original, tamanho e código de região quando disponível.
- Existe uma base parcial de interpretador MIPS III/R4300 em Kotlin, com registradores, memória big-endian simplificada e um subconjunto de instruções aritméticas, multiplicação/divisão, HI/LO, carga/armazenamento, incluindo operações desalinhadas de 32/64 bits, comparação e desvio.
- Testes unitários cobrem detecção de cabeçalho, normalização de byte order e parte do interpretador de CPU, incluindo operações de 64 bits, DADD/DSUB com detecção simplificada de overflow, deslocamentos variáveis de 32/64 bits, MOVZ/MOVN/SYNC, desvios com delay slot, instruções MULT/DMULT/DIV/DDIV com HI/LO e cargas/armazenamentos desalinhados. LDL/LDR/SDL/SDR também são comparados com referências independentes byte a byte em todos os oito offsets.

**Importante:** ainda não executa jogos comerciais de Nintendo 64. A ROM preparada ainda não está conectada a um ciclo de execução. Faltam muitas instruções, exceções, TLB, CP0/FPU, mapeamento de memória fiel ao N64, RSP/RDP, gráficos, áudio, controles e salvamentos.

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

1. Ampliar a cobertura das instruções do R4300 e corrigir diferenças arquiteturais.
2. Implementar exceções, CP0, TLB e um modelo de memória fiel ao N64.
3. Conectar a ROM normalizada a um ciclo de execução seguro.
4. Desenvolver RSP/RDP, gráficos, áudio, controles e salvamento.

Use apenas ROMs que você tem direito de utilizar. Este projeto não inclui jogos nem BIOS proprietárias.
