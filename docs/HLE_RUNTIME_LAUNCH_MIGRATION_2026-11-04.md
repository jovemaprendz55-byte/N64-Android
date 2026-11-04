# Migração do início de jogo para runtime HLE guest — 2026-11-04

## Objetivo

Substituir o fluxo experimental atual de iniciar ROM por um runtime HLE que entregue serviços do sistema operacional N64 ao código guest e consiga executar um jogo real, começando por GoldenEye 007 USA (CIC 6102/7101), sem PIF ROM/BIOS externo e sem executar IPL3.

Não declarar o jogo funcional até observar boot, vídeo e entrada no aparelho real.

## Auditoria do fluxo atual

- O botão principal chama MainActivity.startRomVideoExecution().
- Esse método cria R4300Cpu, chama N64HleBoot.prepare(bus, cpu) e inicia N64RomVideoSession.
- N64HleBoot copia o primeiro MiB da imagem de jogo, valida CRC para CIC 6102 e prepara registradores/PC/stack. Não executa IPL3.
- N64RomVideoSession executa instruções CPU, avança VI por orçamento aproximado de instruções e chama o RSP em pequenos quantuns quando SP HALT está limpo.
- A própria classe documenta que não descobre/agende OSTask em nome do libultra.
- N64HleOsKernel e N64HleCpuScheduler ainda são modelos host-side. Não estão ligados ao ABI guest da ROM e não leem/escrevem OSThread/OSMesgQueue na RDRAM.
- A UI ainda mostra diagnóstico/telemetria no ecrã de execução. Não é uma tela de jogo final.

## Contexto R4300: auditoria corrigida

A recomendação antiga em ARQUITETURA_HLE_SEM_FIRMWARE_2026-11-02.md para implementar snapshot completo do contexto R4300 já foi atendida no código atual:
- R4300Cpu.ContextSnapshot guarda GPR, HI/LO, COP0, FPR/FCR, TLB, habilitação da TLB, PC, delay-slot/branch pendente, fase do divisor Count e estado de reset Random.
- snapshotContext() e restoreContext() validam tamanhos/alinhamento e restauram a TLB.
- A limpeza de LL/SC ao trocar de contexto é intencional para não vazar reservation entre threads.
- Portanto, a lacuna prioritária já não é o snapshot CPU; é o contrato guest ABI e a integração real do dispatcher.

## Etapa 1 implementada: mapa ABI de OSThread

Fonte-alvo: GoldenEye 007 `include/PR/os.h` (revisão 1.149, 1997-12-15) e `src/libultra/os/exception.s`: https://github.com/n64decomp/007/tree/master/include/PR

Adicionado N64HleGuestOsThreadLayout.kt com offsets explícitos do OSThread e __OSThreadContext de 32-bit:
- Header OSThread: links, priority, queue, state, flags, id e fp até 0x1B; o contexto u64 tem alinhamento de 8 bytes e começa em 0x20, após padding em 0x1C.
- Contexto: GPRs 64-bit, HI/LO, SR/PC/Cause/BadVAddr/RCP/FPCSR e 16 slots FP pareados.
- CONTEXT_SIZE=0x190; THREAD_SIZE=0x1B0.
- Estados: STOPPED=1, RUNNABLE=2, RUNNING=4, WAITING=8.
- Prioridades de aplicação: 0 a 127.
- Teste N64HleGuestOsThreadLayoutTest confere offsets, tamanhos, estados e intervalo de prioridade.

Commits:
- layout: dea9afd2c8f6332d9878ece9b18aeb92e3cc1e85
- teste: 7ca78b7e3787889006b96c658635c8b592716d81

Esses offsets são um mapa de ABI, não implementação do dispatcher. Ainda não se lê ou escreve um OSThread guest e ainda não se intercepta nenhuma chamada da ROM.

## Próximas etapas obrigatórias

1. Implementar acesso seguro a estruturas guest na RDRAM com tradução de endereços KSEG0/KSEG1 e validação de faixa/alinhamento.
2. Criar codec de leitura/escrita do OSThread, incluindo campos de estado/prioridade e contexto conforme ABI.
3. Implementar contexto de thread compatível com o formato libultra, validando a relação entre os slots FP pareados e FR do R4300 antes de mapear FPR.
4. Implementar HLE call gates para osCreateThread, osStartThread, osStopThread, osYieldThread, osDestroyThread e APIs de mensagem/eventos, sem inventar endereços de função. A resolução de chamadas precisa de símbolos/padrões ou uma estratégia de interceptação comprovada.
5. Integrar eventos de hardware reais ao kernel: MI/VI/AI/PI/SI/SP. Não acordar threads com eventos fabricados.
6. Integrar submissão de OSTask e execução RSP/RDP após validar o ABI e os descritores.
7. Só então substituir o worker experimental da MainActivity por uma fachada N64HleGameRuntime; manter diagnóstico em tela separada.
8. Testar GoldenEye 007 USA no dispositivo e registrar PC, threads guest, tarefas SP, frames e entradas. Sem esse teste, classificar como build/CI apenas.

## Restrições

- Não remover o caminho atual antes que o novo runtime tenha testes e uma migração funcional.
- Não ligar o kernel host-side ao guest apenas por nome de classe.
- Não fabricar OSTask, MI interrupt, DMA, status de SP ou registradores para forçar avanço.
- Não usar IPL3/PIF ROM externos nem copiar conteúdo de firmware proprietário para o repositório.
- Branch dev/n64-clean-start; main permanece intocada.

## Correção do ABI OSThread e etapa 2 — 2026-11-04

### Correção de layout antes do codec

A inspeção específica do código-fonte de GoldenEye 007 confirmou que o header de 1997 `include/PR/os.h` (revisão 1.149) não contém o campo `thprof`. A estrutura termina o campo `fp` em `0x1B`; como `__OSThreadContext` começa com membros `u64`, o alinhamento de 8 bytes coloca o contexto em `0x20`, deixando quatro bytes de padding em `0x1C`. A assembly de `src/libultra/os/exception.s` confirma esse offset: por exemplo, salva S0 em `0x98`, que é `0x20 + 0x78` (offset de S0 dentro do contexto).

- `THREAD_CONTEXT_PADDING=0x1C` (preservado como palavra bruta; não é ponteiro)
- `THREAD_CONTEXT=0x20`
- `CONTEXT_SIZE=0x190`
- `THREAD_SIZE=0x1B0`
- Prioridade é `s32`; prioridade máxima do sistema 255 e prioridade máxima recomendada para aplicações 127.
- Fontes específicas do alvo: https://github.com/n64decomp/007/blob/master/include/PR/os.h e https://github.com/n64decomp/007/blob/master/src/libultra/os/exception.s

A hipótese anterior de que o campo em `0x1C` seria `thprof` veio de outro header/versão e não corresponde ao ABI-alvo GoldenEye. O codec foi ajustado para preservar o padding sem interpretá-lo como ponteiro.

### Etapa 2 implementada: ponte segura de memória guest

Adicionado `N64HleGuestMemory.kt`:

- Aceita apenas ponteiros diretos KSEG0 (`0x80000000–0x9FFFFFFF`) e KSEG1 (`0xA0000000–0xBFFFFFFF`).
- Aceita a representação de 32 bits zero-extended e sign-extended usada nos valores Long do R4300.
- Converte ambos os segmentos para o mesmo endereço físico RDRAM usando os 29 bits inferiores.
- Rejeita KUSEG, KSSEG, KSEG3 e endereços fora de RDRAM; essas regiões não são mapeadas diretamente por esta classe.
- Verifica alinhamento natural de halfword/word e limites de faixa antes do acesso.
- Leitura/escrita multi-byte preserva big-endian por delegação ao barramento físico.
- Inclui operações de byte e blocos, úteis para estruturas guest como OSThread.

Testes em `N64HleGuestMemoryTest.kt` cobrem KSEG0/KSEG1 alias, ponteiros sign-extended, big-endian, rejeição de segmentos TLB, desalinhamento, limite RDRAM e ponteiros fora de 32 bits.

A classe ainda não implementa tradução TLB nem permite acesso MMIO como se fosse RDRAM. Ainda não há codec OSThread, dispatcher, call gates ou troca de contexto guest.

### CI

- Os runs anteriores das etapas ABI/layout foram concluídos com sucesso: 41900220274, 41900225774 e 41900294202. Eles validam a versão que antecedeu a correção do offset; não validarão retroativamente o layout corrigido.
- A CI das novas classes e da correção do layout foi disparada em execuções separadas. Consultar os resultados antes de declarar aprovação.
- Nenhum teste de GoldenEye no dispositivo foi realizado nesta etapa.


## Etapa 3 implementada: codec guest OSThread

Adicionado `N64HleGuestOsThreadCodec.kt`:

- Lê e grava cabeçalho `OSThread` usando offsets corrigidos: next, priority (s32), queue, tlnext, state/flags (u16), id/fp (s32), palavra de padding em `0x1C` e contexto alinhado em `0x20`.
- Lê e grava GPRs de 64 bits presentes no ABI: AT, V0/V1, A0–A3, T0–T9, S0–S7, GP, SP, S8 e RA.
- GPR zero, K0 e K1 não existem no bloco salvo e são expostos como zero na leitura; são ignorados na escrita.
- HI/LO como 64 bits; SR/PC/Cause/BadVAddr/RCP/FPCSR como 32 bits.
- O mapper agora converte os 16 slots FP para FGRs FR=0 conforme o manual VR4300: FGR par contém a palavra baixa e FGR ímpar a palavra alta. FPCSR vai para FCR31; FR=1 é rejeitado. Quando `fpUsed=0`, os bancos FPR/FCR do template são preservados.
- Ponteiros reais do cabeçalho são tratados como 32 bits; a palavra de padding em `0x1C` é preservada sem interpretação. escrita aceita forma zero-extended ou sign-extended e rejeita valores maiores que 32 bits.
- Valida a faixa completa do OSThread e estado/flags/ponteiros antes da primeira escrita. A classe não altera filas, prioridades, scheduler nem CPU.

Testes em `N64HleGuestOsThreadCodecTest.kt` cobrem round-trip, offsets físicos do padding/context/PC, signed priority/id, GPRs salvos/omitidos, pares FP e pointers sign-extended, limite da estrutura e rejeição prévia de valores inválidos. `N64HleGuestOsThreadContextMapperTest.kt` cobre a conversão FR=0 e rejeição FR=1.

Commits do codec e testes:
- codec: `888784e76cbc0c1657d3f249667b3ac4c9303ccc`
- validação dos ponteiros: `26f6c0e346470749494b941ca632176fb3b1d539`
- teste: `68630641cb13cdb9a344d00115dbdfe8eb2a45fe`
- prevalidar todos os ponteiros antes de escrever: `2a303d27e61960832a2d0ab2d1c28ba84a512974`
- teste de ponteiro tardio inválido: `dc17b95513905a61d14567d91255da8f6ce86ee7`

A CI dos commits de codec/testes está em andamento; consultar o HEAD e os runs mais recentes antes de declarar aprovado.


## Etapa 4 — mapper OSThread ↔ ContextSnapshot e correção FR=0 — 2026-11-04

Adicionado `N64HleGuestOsThreadContextMapper.kt` para converter o contexto guest em `R4300Cpu.ContextSnapshot` e vice-versa.

Durante a validação, o manual do VR4300 mostrou que em FR=0 o FGR par guarda os 32 bits inferiores e o ímpar os 32 bits superiores. A implementação anterior de `readFprDouble/writeFprDouble` fazia o inverso. Isso foi corrigido em `R4300Cpu.kt`; os testes COP1 de memória e aritmética foram atualizados.

- Status.FR=1 é rejeitado pelo mapper; o contrato libultra usado aqui é FR=0.
- Cada slot FP de 64 bits do contexto guest é dividido em FGR par (low 32) e ímpar (high 32).
- FPCSR é mapeado para FCR31.
- Quando `fpUsed=0`, os bancos FPR/FCR do snapshot-template são preservados, como exige a política de dispatch que não restaura FP para thread sem uso do coprocessador.
- GPRs salvos, HI/LO, Status, Cause, BadVAddr e PC são mapeados. K0/K1 são omitidos/zerados.
- TLB, Count divider, random-reset state e outros campos ausentes no ABI são herdados do template. Delay-slot pendente é limpo porque OSThread guarda PC de retomada, não um branch em voo.
- RCP mask permanece no campo guest separado; não foi incorporado ao snapshot CPU.
- Isso ainda não implementa scheduler, fila, troca de contexto ao vivo ou call gates.

Fonte FPU: manual VR4300, seção 17.3 — FR=0 usa registradores pares para low-order e ímpares para high-order: https://n64dev.org/p/U10504EJ7V0UMJ1.pdf

Commits:
- CPU FR=0 corrigido: `9749f5db726c8516ba9eaf2ad4f466c5cef8905e`
- teste LDC1/SDC1: `04b9a684d7c844984e280dc0f9f1a23b17abc023`
- testes COP1 aritmética: `80ce285307d29e136a5ea8debd856f7dfae35f53`
- mapper atualizado: `d0277d8eb2d2632de3b07a4010ccca2c76cf4fef`
- testes mapper: `c1360ededcf6896e0c8e69c83e01b91333a68e0f`

CI dos commits está em andamento; confirmar o run mais recente antes de declarar verde.


## Etapa 5 — primitivas de fila de threads guest

Adicionado `N64HleGuestThreadQueue.kt`, implementando em memória guest o comportamento observado no libultra de GoldenEye:

- `enqueue(queueAddress, threadAddress)`: insere por prioridade decrescente; threads de mesma prioridade permanecem FIFO porque a busca avança enquanto prioridade existente é maior ou igual à nova.
- `thread.queue` recebe o endereço do word que contém a cabeça da fila (`OSThread**`), não o endereço de um nó.
- Suporta tail sentinel com prioridade `-1`, como `__osThreadTail`.
- `pop` remove a cabeça e retorna o nó removido, inclusive o sentinel; não limpa o campo `thread.queue`.
- `dequeue` remove o nó sem limpar `thread.queue` nem reescrever `thread.next`, reproduzindo o comportamento da função de baixo nível.
- Detecta ciclo e inserção duplicada para evitar corrupção; valida ponteiros, alinhamento de 8 bytes e faixa completa do OSThread antes de mutações.
- Não altera estado RUNNABLE/WAITING/RUNNING, não faz dispatch, não mantém active queue e não implementa OSMesgQueue.

Fontes do alvo:
- `__osEnqueueThread`, `__osPopThread` e `__osDispatchThread`: https://github.com/n64decomp/007/blob/master/src/libultra/os/exception.s
- `osStartThread`, `osStopThread`, `osYieldThread`, `__osDequeueThread`: https://github.com/n64decomp/007/tree/master/src/libultra/os

Testes `N64HleGuestThreadQueueTest.kt`: prioridade decrescente, FIFO para empates, sentinel, pop/dequeue com semântica libultra, duplicação, prioridade inválida, ciclos e ponteiros inválidos.

Commits: queue `6606f3dca938e5fca90c6725ca5b439bc6ec9c67`; testes `0521a1c4ffc0a646fabdfc52a54f1e8ba2e54380`. CI em andamento; consultar antes de declarar aprovação.
