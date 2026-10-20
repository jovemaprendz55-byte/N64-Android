# N64-Android

Aplicativo Android para desenvolver, de forma incremental, um emulador de Nintendo 64.

## Estado atual

- O app permite selecionar uma ROM pelo seletor de arquivos do Android.
- A validação verifica cabeçalho reconhecido, tamanho mínimo de 4 KiB, alinhamento de 4 bytes e limite de 64 MiB.
- Ao selecionar uma ROM válida, o app converte seus bytes para a ordem big-endian Z64 e mantém a ROM normalizada em memória.
- O app mostra título, formato original, tamanho e código de região quando disponível.
- Existe uma base parcial de interpretador MIPS III/R4300 em Kotlin, com registradores, memória big-endian simplificada, reset do estado da CPU com preservação da RAM e um subconjunto de instruções aritméticas, multiplicação/divisão, HI/LO, carga/armazenamento, incluindo operações desalinhadas de 32/64 bits, comparação e desvio.
- Endereços efetivos da CPU são validados antes da conversão para índice de memória, evitando truncamento indevido. ROM Z64 normalizada pode ser mapeada em modo somente leitura no domínio físico do cartucho e lida pelo barramento.
- Testes unitários cobrem detecção de cabeçalho, normalização de byte order e parte do interpretador de CPU, incluindo operações de 64 bits, DADD/DSUB com detecção simplificada de overflow, deslocamentos variáveis de 32/64 bits, MOVZ/MOVN/SYNC, desvios com delay slot, instruções MULT/DMULT/DIV/DDIV com HI/LO e cargas/armazenamentos desalinhados. LDL/LDR/SDL/SDR também são comparados com referências independentes byte a byte em todos os oito offsets.
- Foi adicionada uma representação imutável do controle N64 com bits dos botões, eixos analógicos assinados e serialização dos quatro bytes de resposta; ela ainda não está ligada ao protocolo SI/PIF nem à interface de toque.
- Foi criado um banco inicial de registradores VI acessível pelo barramento de memória, com leitura de framebuffer RGBA5551/RGBA8888 a partir da RDRAM. A altura do quadro ainda é informada externamente; temporização, filtros do VI, RDP e sincronização não estão implementados.
- O RSP parcial inclui deslocamentos escalares variáveis SLLV/SRLV/SRAV, multiplicação/divisão escalar com HI/LO (MULT/MULTU/DIV/DIVU, MFHI/MFLO/MTHI/MTLO), saltos com link JALR, BLTZAL e BGEZAL com slot de atraso, transferências COP0, operações vetoriais básicas, BREAK e transferências vetoriais LBV/SBV (byte), LSV/SSV (short), LLV/SLV (4 bytes) e LDV/SDV (8 bytes). A ALU vetorial preserva cópias das origens para suportar alias entre origem/destino com swizzle; testes unitários cobrem esse caso e as transferências de memória implementadas. Ainda faltam as demais operações de memória vetorial e a execução completa de microcódigo/tarefas RSP.
- Foi adicionada `N64GameSurfaceView`, uma camada Android para apresentar um framebuffer convertido na tela. Ela ainda não recebe quadros de um loop de jogo, portanto não representa renderização de jogos funcionando.

**Importante:** ainda não executa jogos comerciais de Nintendo 64. A ROM Z64 já pode ser mapeada no barramento e o R4300 possui execução limitada por instruções para diagnóstico. O VI agora consegue copiar formatos simples de framebuffer da RDRAM, mas nenhum jogo gera esses quadros ainda. Faltam inicialização PIF/boot real, ciclo por quadro, integração dos controles com SI/PIF, muitas instruções, exceções, TLB, CP0/FPU, mapeamento completo de memória, RSP/RDP, áudio e salvamentos.

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

### SI/PIF controller input (partial)

The memory bus now models a basic SI DMA path to and from the 64-byte PIF RAM. The PIF command handler supports controller 0 status (Joybus command `0x00`) and button/stick reads (`0x01`), sourcing the four-byte response from `N64ControllerPort`. Tests cover the basic response bytes and an out-of-range DMA.

This is only a first input-protocol slice: the PIF boot ROM/authentication, accessory commands, additional controller channels, SI interrupts/timing, and integration of touch controls into the running Android game screen are still missing. It does not make commercial ROMs bootable by itself.

### Build validation workflow

Added `.github/workflows/android-ci.yml` to run the Android unit-test task and assemble a debug APK on pushes, pull requests, or manual workflow dispatch. The workflow is committed, but its first run has not yet been confirmed, so build/test success is still unverified.
