# Diagnóstico do caminho OSTask/SP — 2026-11-02

Branch: `dev/n64-clean-start`
Repositório: `jovemaprendz55-byte/N64-Android`
Escopo: inspeção estática do caminho ativo de execução da ROM. Nenhum código Kotlin foi alterado nesta etapa; nenhum APK foi instalado ou testado.

## 1. Descoberta principal

O fluxo iniciado pela interface Android em `MainActivity` faz:

1. Cria `R4300Cpu(bus)`.
2. Executa `N64HleBoot.prepare(bus, cpu)`.
3. Cria `N64RomVideoSession(bus, cpu, ...)`.
4. Executa `session.execute()`.

Nesse caminho não há criação nem chamada de `N64SpTaskRunner`, `startAndRun(taskAddress, ...)), nem leitura de uma fila de OSTask pela aplicação.

Isso, isoladamente, **não prova um defeito**: em execução normal, o próprio software da ROM/libultra deve programar os registradores SP por MMIO quando submete uma tarefa. O runner existente é uma ponte explícita de teste/execução para um endereço de OSTask conhecido; ele não é um scheduler automático e não deve ser chamado com endereço inventado.

## 2. O que o código realmente faz

### MainActivity.kt

O caminho ativo de ROM cria somente CPU + HLE boot + `N64RomVideoSession`. A tela atualiza contadores CPU/RSP/VI e diagnósticos, mas não constrói um scheduler de tarefas.

### N64RomVideoSession.kt

Depois de cada instrução CPU, incrementa contagem e avança VI. A cada `rspPollInterval` (padrão 4096 instruções), chama `pumpRsp()`.

`pumpRsp()`:
- consulta `bus.diagnosticSpStatus()`;
- se `SP_STATUS_HALT` estiver ativo, descarta a referência ao core RSP e retorna;
- se SP estiver liberado, cria/reutiliza `N64RspCore` e executa quantum limitado de instruções.

Portanto, a sessão oferece execução cooperativa do RSP **após** o software liberar SP. Ela não descobre OSTask, não retira tarefas de fila e não inicia o bootstrap automaticamente.

### N64SpTaskRunner.kt / N64SpTaskBootstrap.kt

Existe uma rotina explícita que:
- recebe endereço de OSTask como argumento;
- copia o descritor de 64 bytes para DMEM;
- carrega microcódigo de boot para IMEM;
- define SP PC;
- limpa BROKE/HALT;
- executa RSP com orçamento limitado.

Mas esse caminho requer endereço válido fornecido pelo chamador. Não foi encontrado uso dele no caminho ativo de `MainActivity`.

### N64Bus.kt

Há tratamento de escrita MMIO para:
- `SP_MEM_ADDR`;
- `SP_DRAM_ADDR`;
- `SP_RD_LEN` / `SP_WR_LEN`;
- `SP_PC`;
- `SP_STATUS`;
- `DPC_START` / `DPC_END`.

As transferências SP DMA são síncronas e simplificadas. Uma escrita SP_STATUS aplica comandos de clear/set nos bits de status. Uma escrita DPC_END chama o processador DPC síncrono.

Assim, o próximo teste deve determinar se a CPU da ROM chega a emitir essas escritas. Os logs analisados até agora dizem que não há acessos SP/DPC nessa sessão.

## 3. Conclusão sustentada

O bloqueio observado está antes da execução do RSP: o SP permanece HALT e a ROM não gera escritas MMIO SP/DPC registradas.

Não há evidência suficiente para concluir que:
- `N64SpTaskRunner` deveria ser invocado automaticamente;
- o endereço de OSTask esteja conhecido;
- a causa seja exclusivamente TLB;
- a solução seja forçar SP_STATUS a limpar HALT;
- uma tarefa sintética possa substituir o fluxo real do sistema operacional da ROM.

## 4. Próxima investigação

1. Rastrear o fluxo da CPU desde o entry point HLE até a entrada no loop idle `0x7000071C`, identificando chamadas, retornos e mudanças de contexto.
2. Identificar rotinas de inicialização do sistema operacional, criação/ativação de threads e operações de fila no código executado pela ROM.
3. Capturar snapshots limitados de GPRs, SP, RA, PC e pilha em pontos de chamada relevantes, sem despejar milhões de instruções.
4. Adicionar contadores totais de escritas SP/DPC e, se necessário, watchpoints de endereços, mantendo o histórico limitado como amostra.
5. Verificar se a CPU executa instruções necessárias à rotina do scheduler e se exceções/retornos desviam o fluxo.
6. Só depois que um descritor OSTask real for identificado, validar seus campos e testar o bootstrap com endereço proveniente da ROM.
7. Criar teste reproduzível antes de qualquer alteração comportamental.

## 5. Estado

- Inspeção estática do caminho MainActivity → HLE → CPU session concluída.
- Runner/Bootstrap existem, mas não são chamados pelo caminho ativo da ROM.
- Polling cooperativo do RSP existe, condicionado à liberação de SP.
- MMIO SP/DPC está implementado em nível simplificado.
- Nenhuma alteração Kotlin nesta etapa.
- Nenhum APK novo de correção.
- `main` não foi alterada.
