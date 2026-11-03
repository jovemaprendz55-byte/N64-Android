

## Auditoria de integração OSTask — 2026-11-03

A inspeção posterior encontrou um executor SP já implementado, portanto a tarefa não é criar outro bootstrap do zero:

- `N64SpTaskBootstrap` carrega um descritor de endereço RDRAM explícito, copia-o para DMEM + 0xFC0, carrega o boot microcode na IMEM e libera SP HALT.
- `N64SpTaskRunner` executa um orçamento de instruções RSP, acompanha DMA de microcódigo para IMEM e reconhece a entrada no ucode de tarefa com base no descritor.
- `N64SpTaskLifecycle` e `N64SpTaskDpCoordinator` modelam BREAK, DP_WAIT e Full Sync.
- Testes existentes cobrem bootstrap, BREAK, orçamento esgotado, DMA do ucode de tarefa, transição de microcódigo e limpeza do listener após falha.

**Lacuna real:** `MainActivity` instancia `N64RomVideoSession`, mas essa sessão não utiliza `N64SpTaskRunner`. O caminho contínuo ainda bombeia o RSP quando o bit HALT está limpo, sem descoberta/dispatch de OSTasks libultra. O runner atual exige `taskAddress` conhecido e faz seu próprio bootstrap; chamá-lo sem identificar a sequência real do jogo pode duplicar operações ou iniciar a tarefa errada.

Próximo trabalho: observar eventos reais de SP e identificar quando o descritor de DMEM foi carregado e qual fluxo de boot o jogo está executando. Só então desenhar um despachante integrado, com testes de não duplicação, limites de orçamento, pausa/retomada e DP_WAIT. Não presumir endereço do OSTask, não limpar HALT artificialmente e não substituir o fluxo de boot da ROM sem evidência.
