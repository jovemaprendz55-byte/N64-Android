# N64 Android

Projeto de emulador Nintendo 64 próprio para Android, escrito em Kotlin.

## Estado atual
- Estrutura Android e pipeline de compilação.
- Leitura e normalização dos formatos de ROM Z64, V64 e N64.
- RAM inicial big-endian com testes unitários.
- CPU R4300 inicial com execução de um subconjunto de instruções MIPS (aritmética básica, lógica, desvios com delay slot e acessos load/store) e testes unitários.
- Sem núcleos de emulação de terceiros.

## Limitações desta etapa
Esta base ainda não executa jogos. O mapa completo de memória e barramentos, conjunto completo de instruções R4300, exceções/COP0/TLB, boot PIF/IPL3, integração de ROM com CPU, RSP, RDP, vídeo e áudio serão implementados e testados em etapas separadas. O subconjunto de CPU atual serve para bring-up e não executa jogos comerciais ainda.

## Verificação
O GitHub Actions executa `gradle testDebugUnitTest assembleDebug` em pushes para `main` e `dev/**`.
