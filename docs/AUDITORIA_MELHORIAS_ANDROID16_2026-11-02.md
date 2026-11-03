

## Confirmação de CI e integração OSTask — 2026-11-03

- Runs de CI `41571771059`, `41571794065`, `41571828609`, `41571835295` e `41571841264`: todos `success`.
- O leitor diagnóstico do descritor OSTask e seu teste estão compilando e passando.
- A auditoria de código revelou que `N64SpTaskBootstrap`, `N64SpTaskRunner`, `N64SpTaskMicrocodeTracker`, `N64SpTaskLifecycle` e `N64SpTaskDpCoordinator` já existem e têm testes unitários.
- A lacuna não é ausência total de runner: `MainActivity` usa `N64RomVideoSession`, e esta não conecta o fluxo CPU/libultra ao `N64SpTaskRunner`. O RSP da sessão é apenas cooperativamente bombeado quando HALT está limpo.
- O runner atual requer taskAddress explícito e executa bootstrap próprio. Integrá-lo sem observar como a ROM carrega o descritor e inicia o SP pode duplicar bootstrap/DMAs. Não fazer dispatch por heurística de endereço.
- Próxima etapa: instrumentar/interpretar sequência real de SP_STATUS, SP_PC, SP_RD_LEN e descriptor DMEM com ROM local; projetar integração não duplicativa e testes de regressão. Sem teste real no Galaxy A15, compatibilidade GoldenEye permanece não confirmada.
