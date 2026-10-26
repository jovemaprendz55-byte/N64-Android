# N64 Android

Projeto de emulador Nintendo 64 próprio para Android, escrito em Kotlin.

## Estado atual
- Estrutura Android e pipeline de compilação.
- Leitura e normalização dos formatos de ROM Z64, V64 e N64.
- RAM inicial big-endian com testes unitários.
- Estado inicial separado da CPU R4300.
- Sem núcleos de emulação de terceiros.

## Limitações desta etapa
Esta base ainda não executa jogos. O mapa completo de memória, instruções R4300, COP0/TLB, boot PIF/IPL3, RSP, RDP, vídeo e áudio serão implementados e testados em etapas separadas.

## Verificação
O GitHub Actions executa `gradle testDebugUnitTest assembleDebug` em pushes para `main` e `dev/**`.
