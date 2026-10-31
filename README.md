# N64 Android

Projeto de emulador Nintendo 64 próprio para Android, escrito em Kotlin, sem incorporar núcleos de emulação de terceiros.

## Estado atual

- Estrutura Android e pipeline de compilação/testes pelo GitHub Actions.
- Leitura e normalização de ROMs nos formatos Z64, V64 e N64.
- RAM e barramento inicial big-endian, com janelas para ROM de cartucho e PIF.
- CPU R4300 em desenvolvimento: subconjunto de instruções MIPS de 32 e 64 bits, aritmética, lógica, desvios com delay slot e operações load/store.
- Implementação inicial de COP0, exceções, TLB e instruções de gerenciamento de TLB, com testes de regressão.
- Transferência inicial de dados do cartucho para RDRAM pelo PI DMA.

## Limitações atuais

**Esta versão ainda não está pronta para executar jogos comerciais.** A CPU implementa apenas um subconjunto das instruções e o barramento/periféricos ainda são parciais. Ainda faltam etapas importantes, incluindo boot completo pelo PIF/IPL3, RSP, RDP, geração de vídeo, áudio e integração suficiente dos componentes para executar jogos.

O objetivo das etapas atuais é construir e validar a base do emulador gradualmente, com testes automatizados, antes de avançar para a compatibilidade com jogos.

## Verificação

O GitHub Actions executa `gradle testDebugUnitTest assembleDebug` em pushes para `main` e `dev/**`. O trabalho em andamento deve ser feito na branch `dev/n64-clean-start`; não mesclar em `main` sem autorização.

## Continuidade entre chats

O histórico técnico, estado atual, decisões, limitações, builds e próximos passos do projeto está em [`docs/CONTINUIDADE_CHAT.md`](docs/CONTINUIDADE_CHAT.md). Leia esse documento antes de continuar o desenvolvimento em outro chat. Atualize-o a cada avanço relevante. Todo trabalho deve permanecer em `dev/n64-clean-start`; não alterar `main` sem autorização explícita.
