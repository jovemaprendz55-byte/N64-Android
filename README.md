# N64 Android

Projeto de emulador Nintendo 64 próprio para Android, escrito em Kotlin, sem incorporar núcleos de emulação de terceiros.

## Estado atual

- Estrutura Android e pipeline de compilação/testes pelo GitHub Actions.
- Leitura e normalização de ROMs nos formatos Z64, V64 e N64.
- RAM e barramento inicial big-endian, com janelas para ROM de cartucho e PIF.
- CPU R4300 em desenvolvimento: subconjunto de instruções MIPS de 32 e 64 bits, desvios com delay slot, operações load/store, exceções e TLB básica.
- HLE de boot: identifica um conjunto limitado de CICs, prepara os parâmetros de baixa memória e copia o payload inicial do cartucho para RDRAM **sem executar IPL3 original nem depender da PIF ROM/firmware do console**.
- Barramento inicial com RDRAM, cartucho e MMIO parcial para SP, DPC, MI, VI, AI, PI, SI e RI.
- RSP independente em desenvolvimento, com subconjunto escalar e vetorial; bootstrap explícito de OSTask e rastreamento experimental de microcódigo.
- RDP parcial com parser de display lists, comandos de fill e rasterização experimental de triângulos planos RGBA16.
- Leitura experimental de framebuffer VI e apresentação de frames na interface Android.
- Testes unitários para CPU, TLB, HLE, barramento, DMA, RSP, RDP, VI, ROM e sessões de execução.

## Limitações atuais

**Esta versão ainda não está pronta para executar jogos comerciais.** O HLE substitui somente parte do handoff de boot; não reproduz todos os efeitos de inicialização de IPL3/PIF, calibração de RDRAM, cache ou temporização real. A CPU, COP0/TLB, RSP, RDP, DMA e periféricos continuam sendo implementações parciais, com temporização simplificada. O áudio, Joybus/controles, scheduler completo de threads/OSTasks e pipeline gráfico completo ainda não estão implementados. Não há comprovação de compatibilidade ou jogabilidade de GoldenEye no aparelho.

O objetivo é avançar por etapas verificáveis, mantendo a inicialização HLE independente de firmware original, com testes automatizados e validação em aparelho separada da CI.

## Verificação

O GitHub Actions executa `gradle testDebugUnitTest assembleDebug` em pushes para `main` e `dev/**`. O trabalho em andamento deve ser feito na branch `dev/n64-clean-start`; não mesclar em `main` sem autorização.

## Continuidade entre chats

O histórico técnico, estado atual, decisões, limitações, builds e próximos passos do projeto está em [`docs/CONTINUIDADE_CHAT.md`](docs/CONTINUIDADE_CHAT.md). Leia esse documento antes de continuar o desenvolvimento em outro chat. Atualize-o a cada avanço relevante. Todo trabalho deve permanecer em `dev/n64-clean-start`; não alterar `main` sem autorização explícita.
