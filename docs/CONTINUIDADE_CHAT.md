

## 58. CI verde e auditoria do executor OSTask — 2026-11-03

- Confirmadas como **success** as cinco execuções de CI dos commits de diagnóstico OSTask e documentação: runs `41571771059` (código), `41571794065` (teste), `41571828609` (plano), `41571835295` (continuidade) e `41571841264` (auditoria).
- A compilação/testes automatizados passaram; isso não é teste de ROM nem de hardware Android.
- Auditoria de integração identificou componentes que já existem e não devem ser reescritos sem necessidade:
  - `N64SpTaskBootstrap`: copia o descritor OSTask de RDRAM para DMEM + 0xFC0, carrega boot microcode na IMEM, define SP_PC=0 e libera HALT.
  - `N64SpTaskRunner`: executa o boot microcode por orçamento limitado, rastreia DMA RDRAM→IMEM e identifica a entrada no ucode da tarefa antes de executar o restante.
  - `N64SpTaskMicrocodeTracker`, `N64SpTaskLifecycle` e `N64SpTaskDpCoordinator`: acompanham transferência de ucode, transição boot→task, BREAK e DP_WAIT/Full Sync.
  - `N64RomVideoSession` ainda NÃO usa esse executor. Mantém um RSP cooperativo genérico acionado pelo estado de SP HALT; portanto o fluxo real de OSTask da CPU ainda não está integrado à sessão contínua da ROM.
- O `N64Bus.writeSpStatusCommand()` apenas aplica comandos de estado; não emite evento de início de tarefa. Os SP DMAs já notificam um listener após a transferência síncrona. Isso permite uma integração futura, mas exige distinguir o bootstrap iniciado pelo jogo de um executor host-side para não duplicar DMA ou executar duas vezes o mesmo microcódigo.
- Próxima etapa obrigatória: inspecionar os traces/ordem de writes SP_STATUS, SP_PC, SP_RD_LEN e SP DMEM em execução real da ROM; então desenhar e testar um despachante que observe eventos reais sem inventar taskAddress nem limpar HALT por conta própria. O runner atual recebe taskAddress explícito e não pode ser chamado com endereço presumido.
- Arquivos inspecionados: `N64Bus.kt`, `N64RomVideoSession.kt`, `N64SpTaskRunner.kt`, `N64SpTaskBootstrap.kt`, `N64SpTaskDescriptor.kt`, `N64SpTaskMicrocodeTracker.kt`, `N64SpTaskLifecycle.kt`, `N64SpTaskDpCoordinator.kt`, `MainActivity.kt` e testes do runner/sessão.
- Região confirmada: GoldenEye 007 USA. Nenhuma ROM real foi executada no Galaxy A15 Android 16; nenhum jogo está confirmado como funcional.
- Branch exclusiva: `dev/n64-clean-start`; `main` não foi alterada.
