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


## Verificação consolidada — 2026-11-04

- Run GitHub Actions: `41903604215` — **success** no commit `36aeaf8912017ed12d9351703b0354d700e3eb32`.
- A suíte completou 671 testes sem falhas; `assembleDebug` e upload do APK também passaram.
- Artifact `n64-android-debug-apk`: ID `13360140948`, 1,061,938 bytes, SHA-256 `6477f7ccbaf66b6992d2646648738b48fc0563127345c3a1c22fd423bfe5336d`, expira em 2026-11-18.
- Run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41903604215
- Isso valida build e testes unitários. Não valida execução de GoldenEye no aparelho, troca de contexto em runtime real, dispatch de threads ou APIs libultra.


## Etapa 6 — transições de estado e seleção de thread — 2026-11-04

Implementado `N64HleGuestThreadScheduler.kt` com endereços globais explicitamente fornecidos pelo chamador (endereços de words que contêm ponteiros; nenhum endereço simbólico foi inventado):

- `registerCreatedThread`: replica o prepend em `__osActiveQueue` via `OSThread.tlnext`, com detecção de duplicação/ciclo.
- `startThread`: implementa ramos STOPPED/WAITING/RUNNABLE/RUNNING de GoldenEye `osStartThread`, inclusive reativação de fila anterior e teste de preempção por prioridade.
- `stopThread`: remove RUNNABLE/WAITING da fila proprietária; para a thread corrente marca STOPPED e solicita próxima seleção.
- `yieldThread`: marca a corrente RUNNABLE, reinsere por prioridade/FIFO e seleciona a próxima.
- `dispatchNext`: retira a cabeça da run queue, marca RUNNING e atualiza o word guest `__osRunningThread`; quando a fila contém apenas o sentinel, o modelo limpa o ponteiro corrente e retorna sem thread selecionada.
- Todas as operações usam `N64HleGuestMemory`, codec OSThread e filas guest já testados.

Limites intencionais: isso ainda é o modelo de estado/filas e decisão de dispatch, não uma troca de contexto real. Não salva/restaura R4300 ContextSnapshot, não executa `__osEnqueueAndYield` assembly, não mascara interrupções e não é chamado por ROM call gates. O chamador precisa fornecer os endereços reais de `__osRunQueue`, `__osActiveQueue`, `__osRunningThread` e `__osThreadTail` resolvidos do jogo.

Testes `N64HleGuestThreadSchedulerTest.kt`: active queue prepend; start inicial; preempção somente por prioridade maior; FIFO em yield de prioridade igual; stop de thread runnable e corrente; caminho STOPPED com fila anterior; despertar de WAITING.

Fontes diretas GoldenEye:
- `createthread.c`: https://github.com/n64decomp/007/blob/master/src/libultra/os/createthread.c
- `startthread.c`: https://github.com/n64decomp/007/blob/master/src/libultra/os/startthread.c
- `stopthread.c`: https://github.com/n64decomp/007/blob/master/src/libultra/os/stopthread.c
- `yieldthread.c`: https://github.com/n64decomp/007/blob/master/src/libultra/os/yieldthread.c
- `thread.c` e rotinas de enqueue/pop/dispatch em `exception.s`: https://github.com/n64decomp/007/tree/master/src/libultra/os

Commits: scheduler `449446f799934f9a4ca90ea9ef7f11e78b2a3938`; testes `8b44afefa94580f3af5e90c23ad4076c49b8c942`; limpeza do running pointer vazio `602ab8d9d03b09fcb537776a9b9c65acc91a5084`; teste de regressão `af94314c001a2eccd3727393c16f3534f02a0996`.

CI final: run `41906618830` — success; `gradle testDebugUnitTest assembleDebug` concluído. APK artifact `13360316775`, 1,065,632 bytes, SHA-256 `38d891b491c27ddbf7cefcb5a0938e901de3647879508a2270391fad3ca17662`, expira 2026-11-18. Run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41906618830

Próxima etapa: codec e operações de OSMesgQueue (mtqueue/fullqueue, ring buffer, send/recv/jam), incluindo bloqueio/desbloqueio usando as transições acima. Depois disso, revisar call gate e salvar/restaurar contexto antes de integrar o runtime à MainActivity.


## Etapa 7 — OSMesgQueue guest: ring buffer, espera e wake — 2026-11-04

Adicionados:
- `N64HleGuestOsMesgQueue.kt`: layout de seis words (0x18 bytes) e codec validado para `mtqueue`, `fullqueue`, `validCount`, `first`, `msgCount` e buffer de mensagens.
- `N64HleGuestMessageQueue.kt`: operações guest de `send`, `receive` e `jam`, buffer circular de mensagens de 32 bits, NOBLOCK/BLOCK, filas separadas para receivers esperando fila vazia e senders esperando fila cheia.
- `N64HleGuestThreadScheduler.blockRunningThread`: marca corrente WAITING, enfileira na wait queue fornecida e seleciona próxima thread. Ao liberar espaço/mensagem, a fila retira um waiter e chama `startThread`.

Comportamento implementado:
- `create` exige capacidade positiva, valida buffer inteiro e inicializa as duas wait queues com tail sentinel.
- `send`: escreve no fim lógico do ring buffer; fila cheia retorna WOULD_BLOCK ou bloqueia na fullqueue.
- `receive`: lê do início; fila vazia retorna WOULD_BLOCK ou bloqueia na mtqueue.
- `jam`: insere na frente; fila cheia usa a mesma fullqueue.
- Após send/jam, acorda no máximo um receiver; após receive, acorda no máximo um sender.
- Um resultado BLOCKED significa que o estado guest foi enfileirado e o scheduler mudou a seleção. O método host não suspende uma stack Kotlin: o guest continuation deverá ser retomado e repetir/continuar a operação quando a thread for despachada. Isso ainda não é um call gate funcional.

Fontes:
- ABI OSMesgQueue e macros MQ_IS_EMPTY/MQ_IS_FULL: https://github.com/n64decomp/007/blob/master/include/PR/os.h
- osCreateMesgQueue: https://github.com/n64decomp/007/blob/master/src/libultra/os/createmesgqueue.c
- osSendMesg: https://github.com/n64decomp/007/blob/master/src/libultra/os/sendmesg.c
- osRecvMesg: https://github.com/n64decomp/007/blob/master/src/libultra/os/recvmesg.c
- osJamMesg: https://github.com/n64decomp/007/blob/master/src/libultra/os/jammesg.c

Testes `N64HleGuestMessageQueueTest.kt`: inicialização do ABI; FIFO e wrap do ring buffer; WOULD_BLOCK; jam na frente; receiver bloqueado e acordado por send; sender bloqueado e acordado por receive, com retry explícito após wake.

Commits: OSMesgQueue layout/codec `fc1e42ac437f9e47b82f21e1a12eea0738972a97`; operações `80f9ed24c5ef236f22106426711fb44a10b0e78c`; testes `137730c5c9655dbdaf66b0b38a6e05f488448e3e`; block/wake scheduler `58f3ec81fcf8fb5b74c8e080a21b228c29bea810`; teste block/wake `501dd7daa259029feb3ba9c8604a360e512be930`.

CI:
- Scheduler block/wake: run `41907048221` success.
- OSMesgQueue codec: run `41907099035` success.
- OSMesgQueue operations: run `41907138157` success.
- Full tests + APK: run `41907192238` success.
- APK artifact `13360159620`, 1,071,294 bytes, SHA-256 `67c177d06f8f03e91d355a8037dd507f2030d4b5a278e9196b0ff8d585622c4a`, expires 2026-11-18.
- Run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41907192238

Remaining: validate ABI with actual GoldenEye symbols/data, integrate true context save/restore and guest call gates, then test in device. Do not claim game launch success.


## Etapa 8 — save/restore real de ContextSnapshot no scheduler — 2026-11-04

Adicionado `N64HleGuestContextSwitcher.kt`:
- `startThread`, `yieldCurrent`, `stopCurrent` e `blockCurrent` coordenam o scheduler guest com o estado real do `R4300Cpu`.
- Quando a decisão troca de thread, captura o snapshot live da thread anterior e grava o contexto ABI via `N64HleGuestOsThreadContextMapper` + `N64HleGuestOsThreadCodec`; usa o PC de continuação explicitamente fornecido e limpa branch-delay pendente.
- Restaura a thread selecionada mapeando OSThread para ContextSnapshot e chamando `R4300Cpu.restoreContext`; TLB/Count-phase e campos não presentes no ABI permanecem do template CPU, e o restore limpa LL reservation.
- Quando não há troca, só atualiza PC via `setProgramCounter` para continuar após a chamada HLE.
- PC de continuação é validado como alinhado e 32-bit.

Testes `N64HleGuestContextSwitcherTest.kt`: preempção por prioridade salva GPR/PC da thread anterior e restaura a nova; stop da corrente restaura thread runnable; bloqueio salva continuação guest e o wake restaura PC/GPR.

Commits: switcher `3828470d7b19ff43da25e90eb1b40b3c38f6f558`; testes iniciais `722435a173676ada12d79eab7aa5ab8e80bf087e`; PC setter `09a2fa67b97f80fb73d1cbbff9f5f494338bcc44`; assertion GPR `161414ce6224680660b06af5e5c2bff211282c12`; PCs guest 32-bit `bd7136eeed45ca01e376194cd4408b3ff9467425`.

CI final: run `41908404540` success — `testDebugUnitTest assembleDebug` passou. APK artifact `13362056253`, 1,072,204 bytes, SHA-256 `96caed88143b02b7312bffdeb214ef7eba43b5e660b73e14e53c34157c6d7b8b`, expira 2026-11-18. https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41908404540

Limites: o switcher ainda não é invocado pelo N64RomVideoSession, não há interceptação de PC nem endereços confirmados de API libultra; o `N64HleGuestMessageQueue` ainda chama o scheduler de estado, não o switcher, então BLOCK/wake ainda não preserva contexto live nesse caminho. A integração só pode avançar depois de um dispatcher com endereços de função comprovados e sem inventar offsets. Nenhum teste GoldenEye no aparelho foi feito.


## Etapa 9 — hook pre-fetch R4300 e dispatcher por endereço verificado — 2026-11-04

- `R4300Cpu.setInstructionBoundaryHook`: callback executado depois de tratar MI interrupt e antes do fetch. Retorno `null` deixa CPU executar instrução guest; custo inteiro não-negativo consome a fronteira e avança Count pelo custo informado.
- `N64HleGuestCallDispatcher`: registry explícito nome→endereço→handler; exige evidência não vazia, endereço alinhado/32-bit, rejeita duplicação por símbolo/endereço e normaliza endereços KSEG sign-extended/zero-extended para a forma signed Long do PC.
- `install/uninstall` liga/desliga registry ao hook pre-fetch. Sem correspondência exata, não há interceptação.
- Nenhum endereço GoldenEye real está registrado. A auditoria `docs/GOLDENEYE_US_CALL_GATE_ADDRESS_AUDIT_2026-11-04.md` conclui que `ge007.ld` + `ld/lib.text.ld.inc` mostram layout e ordem dos objetos, mas não offsets finais dos símbolos; o repositório consultado não contém map final USA. Não estimar endereços.
- Testes: hook pula fetch e consome ciclos; hook null executa normalmente; MI interrupt tem precedência; dispatcher só atende endereço registrado; endereço desconhecido faz fallthrough; duplicações/evidência ausente rejeitadas; canonicalização zero/sign-extended; uninstall.

Commits: hook `188c9810cde874bf599fcdcf55bd66e90df3bccb`; hook tests `bc26c6f0d8d045ce0fd419373b2feb370cc30507`; dispatcher `3fdd66a67839518e1470fc17efac6cf8ab8f7c75`; dispatcher tests `6d083eb2e77720a92279b01ba1e7cb4354cbb919`; canonicalization `9be8c97c28cdc9674e4a07944ec599ce03841ff5`; canonical test `713de767ab449b49f0ab1308cd4aea7a7cd78c37`; address audit `9504029f1c562beaa26953c351e68e8596b33a5f`.

CI consolidada do hook/dispatcher/canonicalização: run `41909064407` success; `testDebugUnitTest assembleDebug` passou. APK artifact `13361058496`, 1,075,346 bytes, SHA-256 `c115251cea935f1815ee9848affafc74712cb4994cdff4a618af523f66260abf`, expira 2026-11-18. https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41909064407

Limites: hook e dispatcher ainda não têm registros GoldenEye, não há call gate real de libultra; message queue ainda não usa ContextSwitcher para bloquear/restaurar contexto live; nenhuma alteração no worker MainActivity; nenhum teste de GoldenEye no dispositivo.
