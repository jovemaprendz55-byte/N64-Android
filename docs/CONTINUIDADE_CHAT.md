# CONTINUIDADE DO PROJETO — N64 ANDROID

> **Documento reconstruído do zero em 2026-11-02.** O conteúdo anterior deste arquivo foi substituído por esta fotografia técnica atual, auditoria e plano de correção. O arquivo foi mantido no mesmo caminho. Este documento passa a ser a referência principal para continuar o projeto em outro chat.

## 1. Identidade e regras do projeto

- Repositório: https://github.com/jovemaprendz55-byte/N64-Android
- Branch de trabalho: `dev/n64-clean-start`
- Não alterar `main`, não mesclar branches e não apagar branches sem autorização explícita.
- Aplicativo pessoal, destinado ao aparelho do usuário: **Poco C85 com Android 16**.
- Pacote Android: `com.jsr.n64android`.
- Objetivo: construir um emulador Nintendo 64 próprio em Kotlin, sem incorporar Mupen64Plus ou outro núcleo de emulação de terceiros.
- Preferência de boot: HLE próprio, sem executar IPL3 original e sem depender da PIF ROM/firmware original.
- Prioridade real: fazer uma ROM avançar corretamente pela inicialização, executar CPU/RSP/RDP e apresentar imagem e som no aparelho. Não gastar tempo priorizando compatibilidade com Android antigo enquanto o alvo é o Android 16 do usuário.
- Nunca mascarar falhas forçando registradores, inventando OSTask, limpando HALT sem evidência ou alterando COP0/TLB só para fazer o log parecer melhor.
- Distinguir sempre: **confirmado no código**, **observado em log**, **hipótese** e **ainda não testado**.

## 2. Estado exato da branch auditada

- Branch: `dev/n64-clean-start`
- HEAD auditado: `d65a14b54ec626d7ba9a27b37963824f895807d7`
- Commit: `Document GoldenEye call path findings and trace limits`
- Árvore Git: 108 arquivos (37 arquivos em `app/src/main`, 59 arquivos de teste, 6 documentos em `docs`, mais arquivos de configuração/raiz e workflow).
- Auditoria: inventário completo da árvore e leitura dirigida dos módulos centrais, fluxo de inicialização, dispositivos, testes e documentação. Os achados abaixo não significam que cada linha dos 108 arquivos tenha sido revisada manualmente.

### CI e APK

- GitHub Actions run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41434025556
- Run ID: `41434025556`
- Commit validado: `d65a14b54ec626d7ba9a27b37963824f895807d7`
- Resultado: **success**.
- Etapas: `gradle testDebugUnitTest assembleDebug` e upload do APK debug.
- Artifact: `n64-android-debug-apk`, ID `13134693490`.
- Isso confirma compilação e testes unitários automatizados nessa revisão. **Não** confirma instalação, estabilidade, boot de GoldenEye, renderização de jogo ou áudio no Poco C85.
- O workflow não executa `lintDebug`.
- O gatilho `push` inclui `main` e `dev/**`; o gatilho `pull_request` inclui somente `main`. PRs cujo destino seja `dev/n64-clean-start` não são cobertos pela regra atual.

## 3. Inventário da arquitetura atual

### Aplicativo e execução

- `MainActivity.kt`: Activity de aproximadamente 1.772 linhas e 115 KB. Reúne menu, seleção/carregamento de ROM, execução, apresentação de vídeo, exportação de logs e diagnósticos antigos/experimentais.
- `N64ExecutionSession.kt`: loop genérico de execução em lotes, cancelamento cooperativo e estado READY/RUNNING/COMPLETED/CANCELLED/FAILED.
- `N64ExecutionWorker.kt`: wrapper de thread para uma sessão.
- `N64RomVideoSession.kt`: caminho contínuo usado para execução de ROM, polling de RSP, progresso e captura VI.
- O caminho principal observado é: **MainActivity → N64HleBoot.prepare → N64RomVideoSession → R4300Cpu.step**, com chamadas periódicas ao polling do RSP e captura VI.
- A Activity também mantém caminhos diagnósticos antigos e testes sintéticos. Eles não devem ser confundidos com o caminho real de jogo.

### ROM e boot HLE

- `N64RomLoader.kt` e `N64RomInspector.kt`: carregamento/identificação e normalização de ROM nos formatos Z64, V64 e N64.
- `N64HleBoot.kt`: identifica um conjunto limitado de CIC/seed, lê o entry point do cabeçalho, copia 1 MiB da ROM (offset `0x1000`) para RDRAM (endereço `0x400`), escreve parâmetros de baixa memória e prepara PC/registradores iniciais.
- O HLE não executa IPL3 original, não copia IPL3 para SP IMEM e não usa PIF ROM. O próprio código declara que calibração de RDRAM, cache, comportamento completo de CIC/PIF e temporização não são reproduzidos.
- Seeds aceitos pelo código: `0x3F` e `0x91`, com mapeamento específico para os campos HLE. Não presumir compatibilidade com todos os CICs.
- O entry point é validado como KSEG0 e dentro da RDRAM instalada.
- O HLE é um handoff parcial, não uma implementação equivalente a todo o boot real do console.

### CPU e TLB

- `R4300Cpu.kt`: interpretador próprio com subconjunto de instruções MIPS III/R4300, operações aritméticas 32/64 bits, desvios/delay slot, load/store, exceções e parte de COP0.
- `R4300Tlb.kt`: modelo de 32 entradas, ASID, páginas pareadas e máscaras de página suportadas.
- O cabeçalho do próprio `R4300Cpu.kt` declara que ainda faltam instruções, semântica completa de exceções em delay slot, COP0/TLB completos e operações de cache.
- Existem históricos diagnósticos de exceções, TLBWI/TLBWR, fluxo de controle, memória e registradores. Parte da instrumentação é especializada em endereços/rotinas observados em diagnósticos anteriores; não equivale a um rastreador genérico de todo o programa.
- Os arrays FPR/FCR existem, mas isso não deve ser confundido com FPU R4300 totalmente implementada.

### Barramento e dispositivos

- `N64Bus.kt`: RDRAM, janela de cartucho, SP DMEM/IMEM e MMIO parcial para SP, DPC, MI, VI, AI, PI, SI e RI.
- SP DMA copia dados de forma síncrona e simplificada; não reproduz latência, filas ou temporização completa.
- PI DMA de cartucho para RDRAM é síncrono e gera interrupção MI simplificada.
- SI/PIF tem cópia básica de 64 bytes; protocolo completo PIF/Joybus e controles não estão implementados.
- AI LEN atualmente consome a escrita sem DMA de áudio ou temporização. Não existe saída de áudio funcional.
- VI avança por contagem aproximada de instruções; não é temporização de ciclo real.
- O DPC é acionado quando a CPU escreve DPC_END. O barramento chama o processador de display list de forma síncrona; não há FIFO/temporização assíncrona completa.

### SP, RSP e OSTask

- `N64RspCore.kt`: interpretador independente do RSP, ainda parcial. Inclui subconjunto escalar MIPS-I, subset de COP2/vetores e operações limitadas de memória vetorial.
- `N64RspVectorUnit.kt`: armazenamento e operações vetoriais parciais.
- `N64SpTaskDescriptor.kt`, `N64SpTaskBootstrap.kt`, `N64SpTaskRunner.kt`, `N64SpTaskLifecycle.kt`, `N64SpTaskMicrocodeTracker.kt` e `N64SpTaskDpCoordinator.kt`: infraestrutura experimental para descrever, preparar, executar e acompanhar uma OSTask.
- O bootstrap explícito exige endereço real de OSTask em RDRAM. Copia os 64 bytes do descritor para DMEM, carrega o boot microcode na IMEM, define SP_PC e libera HALT.
- `N64SpTaskRunner.startAndRun()` existe como ponte explícita/testável, mas **não é chamado pelo fluxo principal MainActivity → N64RomVideoSession**.
- `N64RomVideoSession.pumpRsp()` consulta SP_STATUS. Se HALT estiver ativo, não executa RSP. Se o software convidado liberar HALT, cria/usa o RSP e executa um quantum limitado.
- A sessão não descobre nem agenda OSTasks por conta própria. Isso é uma limitação declarada no comentário da classe. Não significa que devamos fabricar um descritor: a tarefa real precisa ser localizada no fluxo do jogo.

### DPC, RDP e vídeo

- `N64DpcCommandProcessor.kt` lê display lists de RDRAM, separa pacotes e despacha o subconjunto suportado.
- `N64RdpCommandStreamParser.kt` e `N64RdpCommandLength.kt` reconhecem comprimentos de comandos, inclusive comandos multiword que ainda não são executáveis.
- `N64RdpFillProcessor.kt`: suporta estado básico, fill rectangles e um caminho experimental de triângulos planos RGBA16. Não há pipeline gráfico completo: texturas, shading, Z, coverage, blending e combiner não estão implementados.
- `N64ViFramebufferReader` e `N64VideoPresenterView`: leem framebuffer da RDRAM por VI e apresentam bitmap no Android.
- `N64VideoTestPattern` e testes sintéticos validam conversão/captura/apresentação de pixels. Uma imagem de teste sintética **não** comprova que uma ROM esteja renderizando.

## 4. Achados da auditoria — problemas confirmados

### A1 — O caminho de tarefas SP não está integrado como scheduler automático

**Evidência no código**
- A sessão principal executa CPU e chama `pumpRsp()` periodicamente.
- `pumpRsp()` só executa RSP se SP_STATUS.HALT estiver limpo.
- O `N64SpTaskRunner` chama o bootstrap explícito, mas não é invocado no fluxo principal da ROM.
- A sessão declara expressamente que não descobre, enfileira ou agenda OSTasks para libultra.

**Impacto**
- O emulador depende de o próprio software convidado executar corretamente a sequência de preparação/liberação do SP.
- Se o guest não fizer essa sequência, o RSP permanece sem executar; ter um interpretador RSP implementado não basta.

**Correção necessária**
- Primeiro identificar, no fluxo real da ROM, a rotina que deveria montar/enviar a OSTask e confirmar se o descritor existe em RDRAM.
- Registrar writes SP, DMA, SP_PC, SP_STATUS, endereços do descritor e microcode.
- Só depois decidir se falta corrigir o handoff HLE, a CPU/MMIO ou implementar um scheduler. Não iniciar tarefa por endereço presumido.

### A2 — Falha do RSP é mascarada e altera estado do hardware

<badge>Corrigido nesta branch em 2026-11-02</badge>

**Evidência original**
- Em `N64RomVideoSession.pumpRsp()`, o bloco `catch (error: Throwable)` guarda uma mensagem, escreve `SP_STATUS_CMD_SET_HALT` e descarta o core.
- Isso captura também erros graves que não deveriam ser tratados como uma instrução RSP não suportada.

**Impacto**
- O erro original pode ficar escondido e a sessão CPU pode continuar como se o RSP apenas tivesse parado.
- A escrita forçada de HALT muda estado visível do dispositivo e pode contaminar o diagnóstico do comportamento original do guest.

**Correção necessária**
- Não escrever HALT artificialmente no catch.
- Capturar somente exceções esperadas do emulador (por exemplo, instrução RSP ainda não suportada), preservar PC/instrução/estado e marcar a sessão com falha diagnóstica clara.
- Para erros inesperados, propagar a falha ou encerrar a sessão de forma explícita; não engolir `Throwable`.
- Criar testes que provem que a falha é reportada sem modificar SP_STATUS para forçar HALT.

### A3 — Listener de DMA da OSTask não é removido em bloco finally

<badge>Corrigido nesta branch em 2026-11-02</badge>

**Evidência original**
- `N64SpTaskRunner.startAndRun()` registra `bus.setSpDmaListener(tracker::onSpDmaCompleted)`.
- A remoção `bus.setSpDmaListener(null)` ocorre somente após `rsp.run(maxInstructions)) retornar normalmente.

**Impacto**
- Se a execução RSP lançar exceção (por exemplo, opcode/micro-op não suportado), o listener antigo pode permanecer no barramento e receber DMA de uma execução posterior.

**Correção necessária**
- Colocar a execução e leitura do resultado em `try` e remover o listener em `finally`.
- Adicionar teste em que RSP lança uma exceção e, depois, confirmar que o listener anterior não é chamado por um DMA subsequente.

### A4 — RDP e DPC são parciais e síncronos

**Evidência**
- A escrita em DPC_END chama diretamente `dpcCommandProcessor.process()`.
- O processador executa somente comandos suportados, com validação de faixa e tamanho.
- O RDP implementa fill-cycle/retângulos e triângulos planos RGBA16, não o pipeline gráfico completo.
- O próprio comentário de `N64DpcCommandProcessor` lista como ausentes START/END double-buffered completo, XBUS/DMEM DMA, temporização, interrupções completas e FIFO assíncrona.

**Impacto**
- Jogos que enviam comandos RDP fora do subconjunto atual podem falhar ou interromper a execução.
- Não se deve esperar imagem de jogo só porque a captura VI e o desenho sintético funcionam.

**Correção necessária**
- Capturar a primeira display list real enviada pelo jogo: DPC_START, DPC_END, CURRENT e bytes/words da RDRAM.
- Identificar opcode e comprimento do primeiro comando não suportado.
- Implementar comandos em ordem de evidência, com testes de parser, validação e pixels. Não ampliar RDP às cegas.

### A5 — CPU R4300 e TLB continuam incompletos

**Evidência**
- O cabeçalho de `R4300Cpu.kt` declara explicitamente subconjunto, semântica de delay slot incompleta, COP0/TLB parcial e cache não modelado.
- O TLB suporta 32 entradas e um conjunto limitado de PageMask.
- Há testes unitários para muitas instruções, exceções, MI e TLB, mas isso não transforma o interpretador em um R4300 completo.

**Impacto**
- Uma instrução/semântica usada pelo jogo que não esteja implementada pode gerar Reserved Instruction, resultado incorreto ou caminho de exceção incorreto.
- Erros de CPU podem se manifestar depois como loop, ausência de SP ou falha de renderização.

**Correção necessária**
- Quando o log indicar uma instrução ou exceção concreta, reproduzir em teste isolado antes de alterar o decoder.
- Priorizar instruções realmente encontradas na ROM e semântica de delay slot/exceções que o log demonstrar.
- Validar COP0, TLB refill, ERET, LL/SC e operações de cache relevantes com fixtures. Não reescrever a CPU inteira sem necessidade observada.

### A6 — O boot HLE não substitui todos os efeitos do IPL3/PIF

**Evidência**
- O HLE copia payload e define um conjunto de parâmetros/registradores, mas declara ausentes calibração de RDRAM, cache, comportamento completo CIC/PIF e temporização.
- Só aceita o conjunto limitado de seeds implementado.

**Impacto**
- Uma ROM pode avançar além do entry point e ainda depender de estado que o HLE não preparou.
- O fato de PC iniciar em KSEG0 não prova que o ambiente de inicialização seja equivalente ao console real.

**Correção necessária**
- Comparar o estado inicial e as primeiras rotinas executadas com documentação técnica e logs reais, começando pelo primeiro comportamento divergente.
- Validar os campos de baixa memória, valores de CIC, stack, região, RDRAM e conteúdo copiado para cada ROM de teste.
- Não introduzir IPL3/PIF original: manter a decisão HLE e implementar somente os efeitos necessários e demonstráveis.

### A7 — Áudio e controles ainda não estão implementados

**Evidência**
- AI LEN não faz DMA/temporização de áudio.
- O projeto declara áudio, Joybus/controles e protocolo PIF completos como ausentes.

**Impacto**
- Mesmo que a imagem funcione, não haverá experiência completa de jogo com som e entrada de controle.

**Correção necessária**
- Depois de estabilizar boot, CPU, SP/RSP e vídeo, implementar AI DMA/buffer e saída de áudio Android.
- Depois, implementar protocolo Joybus/PIF e mapear controles para botões/analógico.
- Não priorizar áudio antes de a ROM chegar a uma execução estável.

### A8 — Diagnóstico de loop ainda não prova ausência de progresso

**Evidência**
- `N64InstructionTrace` detecta repetição de PC+opcode numa janela limitada.
- O relatório automático já avisa que repetição não prova estado idêntico.
- Snapshot atual de GPR/COP0 é feito no fim da sessão; não mostra a evolução temporal dos registradores.
- Parte do call-path em `R4300Cpu` é filtrada por janelas de endereços específicas e pode não incluir o scheduler/caller que levou ao idle loop.

**Impacto**
- Um loop de espera legítimo, que aguarda interrupção/evento, pode parecer travamento.
- O relatório pode não mostrar a cadeia de chamadas que levou até a espera.

**Correção necessária**
- Adicionar ring buffer genérico e limitado de eventos de controle: J/JAL/JR/JALR, branches tomados, ERET, PC, alvo, RA e A0–A3; capturar também SP e endereço/estado de tarefa quando identificados.
- Fazer snapshots de registradores em intervalos configuráveis e limitados, sem calcular hashes de todos os GPR a cada instrução.
- Preservar instrumentação de baixo custo: sem strings, locks ou alocações pesadas por instrução.
- Criar testes de ordem, limite e ausência de alteração do estado arquitetural.

### A9 — Activity concentra responsabilidades demais

**Evidência**
- `MainActivity.kt` tem aproximadamente 1.772 linhas/115 KB e combina UI, seleção de ROM, execução, exportação e diversos diagnósticos/testes legados.

**Impacto**
- Aumenta o risco de alterar uma tela e quebrar execução ou diagnóstico; dificulta localizar o fluxo realmente ativo.

**Correção necessária**
- Após estabilizar o caminho de execução, separar gradualmente seleção/carregamento de ROM, controlador da sessão, exportação de logs e telas de diagnóstico.
- Não fazer uma refatoração grande junto com correções de CPU/RSP. Cada extração deve manter testes e comportamento.

### A10 — CI não executa Lint e não valida PR para branch dev

**Evidência**
- `.github/workflows/android.yml` executa `testDebugUnitTest assembleDebug`, sem `lintDebug`.
- `pull_request.branches` contém apenas `main`, embora push para `dev/**` seja coberto.

**Correção necessária**
- Em etapa separada, incluir `lintDebug` depois de resolver os avisos reais e adicionar `dev/**` ao gatilho de PR se o fluxo de trabalho passar a usar PRs para essa branch.
- A CI verde atual continua válida para build/teste; não afirmar que Lint foi executado.

## 5. Compatibilidade Android — decisão específica deste projeto

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, Java/Kotlin JVM 17.
- O aparelho de teste do usuário é Android 16. **Não confundir o Android 16 do aparelho com minSdk 26 do APK.**
- A exportação de logs usa `MediaStore.Downloads.EXTERNAL_CONTENT_URI`, API 29+. Isso é um risco de compatibilidade para aparelhos Android 8/9, mas não é um bloqueio no Android 16.
- Como o aplicativo é pessoal e o alvo atual é o Poco C85 Android 16, não gastar esforço agora em suporte a Android antigo. Manter ou elevar minSdk é decisão futura, sem prioridade para o diagnóstico do jogo.

## 6. Histórico de diagnóstico GoldenEye já documentado

Este bloco resume fatos dos logs analisados anteriormente. Não confundir dados de logs antigos com uma nova execução no HEAD atual.

### Log `N64-Android-logs-1793616296890.txt`

- ROM identificada como GOLDENEYE, 12.582.912 bytes, CIC seed `0x3F`, sem PIF ROM.
- CPU executou aproximadamente 16,95 milhões de instruções.
- PC final `0x70000720`; em `0x7000071C` havia `0x1000FFFF` = `BEQ $zero,$zero,-1`, seguido por NOP em `0x70000720`. É um loop de espera de uma instrução; sozinho não prova defeito do decoder.
- O histórico registrou interrupção ExcCode=0 com Cause `0x80000400` (BD + IP2), EPC `0x7000071C`; handler retornou por ERET ao loop. Isso é compatível com espera por interrupção, não prova de deadlock.
- TLB miss em VA `0x7F0BCC28` ocorreu após PI DMA de ROM offset `0xF0B30` para RDRAM `0x36E000`, tamanho `0x2000`. EntryLo0 `0xDB9F` apontava PFN `0x36E`, correspondente à página física `0x36E000`; entrada TLB posterior e execução em VA `0x7F0BCC9C` são evidência de page-in seguido de execução. Não tratar o MISS inicial como prova de bug TLB.
- MI_INTR_MASK recebeu `0xAAA` (SET das seis fontes) e `0x555` (CLEAR das seis fontes); snapshot final tinha máscara `0x3F`. Isso prova atividade de configuração das máscaras, não submissão de OSTask.
- SP ficou `HALT=1`, SP_PC=0, RSP=0 instruções, sem SP DMA, sem writes SP e sem writes DPC registrados naquele log.
- Snapshot final: RA `0x70010A80`, SP `0x803AB738`, PC `0x7000071C`, EPC `0x7000071C`, Cause final `0x80000000`. Cause final não apaga a interrupção anterior registrada no histórico.
- O call-path antigo capturava somente janelas fixas de PC; ausência de scheduler no trecho não prova ausência de chamadas intermediárias.
- **Conclusão correta:** o log demonstra CPU em loop de espera, pelo menos uma interrupção MI/IP2 anterior e ausência de submissão/liberação SP observada naquela sessão. A causa raiz de o guest não ter enviado tarefa não foi determinada.

### Consequência para a investigação

A próxima coleta deve mostrar a cadeia genérica de fluxo de controle anterior à primeira entrada no loop, os registradores de argumentos e os acessos SP/DPC. Procurar descritor OSTask e ponteiros de microcode em memória somente com evidência de endereço/estrutura; não presumir endereço fixo nem iniciar tarefa artificialmente.

## 7. Plano de correção — ordem recomendada

### Etapa 1 — tornar o diagnóstico conclusivo sem mudar a emulação
1. Implementar ring buffer genérico de fluxo de controle (CPU).
2. Incluir snapshots temporais limitados de GPR/COP0 e contexto de argumentos.
3. Manter histórico de MMIO SP/DPC/DMA e correlacionar PC do guest.
4. Testar limites do buffer, ordem cronológica, custo e neutralidade arquitetural.
5. Gerar APK e coletar um novo log de GoldenEye no Android 16.

**Critério de saída:** identificar a rotina que chega ao loop e verificar se ela deveria submeter OSTask, esperar VI/MI ou retornar de uma inicialização incompleta.

### Etapa 2 — corrigir tratamento de falha RSP e listener DMA
1. Remover HALT forçado do catch de `pumpRsp()`.
2. Não capturar `Throwable` indiscriminadamente como erro normal de micro-op.
3. Garantir remoção do listener SP DMA com `finally` no runner.
4. Criar testes de regressão para ambas as falhas.

**Critério de saída:** uma instrução RSP não suportada aparece claramente no diagnóstico, sem mudar artificialmente SP_STATUS nem deixar listener antigo ativo.

### Etapa 3 — resolver o handoff/OSTask real
1. Usar o novo log para localizar a rotina real de submissão de tarefa.
2. Verificar descritor OSTask, endereço, flags, ucodeBoot/ucode/ucodeData/dataPtr e DMA efetivamente escritos.
3. Corrigir o componente responsável demonstrado pelo log: HLE, CPU, MMIO ou bootstrap.
4. Integrar scheduler somente se ficar demonstrado que falta uma etapa de despacho no caminho ativo.

**Critério de saída:** RSP executa uma tarefa real do jogo, com boot microcode e microcode da tarefa observados, sem descritor inventado.

### Etapa 4 — estabilizar RSP e DPC/RDP
1. Resolver instruções escalares/vetoriais RSP que o microcode real pedir.
2. Capturar primeiro comando RDP não suportado.
3. Expandir parser/decoder/rasterizador por opcode e teste.
4. Validar DPC Full Sync, interrupção DP e conclusão de tarefa com estado real.

**Critério de saída:** tarefa gráfica real produz writes de framebuffer verificáveis em RDRAM.

### Etapa 5 — estabilizar VI e apresentação
1. Confirmar VI_ORIGIN, WIDTH, STATUS, H/V_START, X/Y_SCALE e altura calculada com valores do jogo.
2. Distinguir framebuffer inexistente, endereço inválido, formato não suportado e frame repetido.
3. Validar apresentação no Poco C85, sem depender de padrão sintético.

**Critério de saída:** imagem real da ROM chega à tela Android.

### Etapa 6 — CPU/MMU e periféricos conforme evidência
1. Implementar instruções/semânticas CPU que logs e testes mostrarem necessárias.
2. Corrigir TLB/COP0/delay-slot somente com reprodução mínima.
3. Implementar AI/áudio e Joybus/controles após vídeo e execução estáveis.
4. Melhorar temporização de SP/PI/VI/DPC gradualmente.

### Etapa 7 — manutenção
1. Dividir MainActivity por responsabilidade sem refatoração ampla.
2. Adicionar Lint e ajustar workflow de PR dev quando necessário.
3. Atualizar este documento após cada alteração relevante, incluindo commit, CI, APK e teste no aparelho.

## 8. Regras de validação e segurança técnica

- Antes de editar código, identificar arquivo/função e reproduzir a falha em teste unitário sempre que possível.
- Uma correção por vez, com teste de regressão específico.
- Não misturar refatoração visual com alterações de CPU, TLB, RSP ou RDP.
- Não chamar CI verde de compatibilidade de jogo.
- Não chamar frame sintético de renderização de ROM.
- Não concluir que um loop é travamento somente por PC/opcode repetido.
- Não concluir que um TLB miss inicial é bug sem examinar refill e execução posterior.
- Não criar OSTask, alterar SP_STATUS, forçar interrupção ou mudar registradores para contornar sintomas.
- Sempre registrar SHA do commit, resultado da CI e se houve teste real no Android 16.
- Toda alteração fica em `dev/n64-clean-start`; `main` permanece intocada.


## 11. Expansão do núcleo — primeira rodada de correções

**Implementação iniciada após a auditoria, em 2026-11-02.** Branch `dev/n64-clean-start`; `main` não foi alterada.

### Correção C1 — falha RSP não fabrica SP HALT

- Arquivo: `app/src/main/java/com/jsr/n64android/N64RomVideoSession.kt`.
- Antes: `pumpRsp()` capturava `Throwable`, registrava texto, escrevia `SP_STATUS_CMD_SET_HALT` e permitia que a sessão CPU continuasse.
- Agora: registra a falha, descarta a instância local do core e relança a exceção. A sessão geral passa a estado FAILED pelo tratamento existente em `N64ExecutionSession`; SP_STATUS não é alterado artificialmente.
- Novo teste: `rspFailurePropagatesWithoutForcingSpHalt`, em `N64RomVideoSessionTest.kt`.
- O teste usa opcode RSP não suportado, exige exceção, estado FAILED, mensagem diagnóstica e HALT ainda limpo.
- Observação: a captura continua sendo `Throwable` somente para registrar e propagar; não é mais engolida. Futuramente podemos estreitar a captura se houver uma hierarquia de erros de emulação própria.

### Correção C2 — listener de DMA sempre removido

- Arquivo: `app/src/main/java/com/jsr/n64android/N64SpTaskRunner.kt`.
- Antes: `setSpDmaListener(null)` só ocorria se `rsp.run()` terminasse normalmente.
- Agora: a remoção está em `finally`, inclusive quando uma instrução RSP lança exceção.
- `N64Bus.kt` ganhou `diagnosticHasSpDmaListener()`, consulta sem efeito colateral usada para verificar limpeza.
- Novo teste: `dmaTrackerIsDetachedWhenRspExecutionThrows`, em `N64SpTaskRunnerTest.kt`.

### Commits desta rodada

- `5dc294d530871a5837e5aa67f1eb9d24d9177357` — propagar falha RSP sem fabricar HALT.
- `c957286d3325719efb14decda6d594c0b455e3f5` — expor estado do listener para teste.
- `6a00f81dc892e5a0141d59a191e94afb6e7a1d94` — remover listener em finally.
- `3bc8a24e64e858af10933ad7cc882619a0da9279` — teste de falha RSP preservando SP.
- `cf7a97d73a5f1c075aa549d46ef32880fcfaf096` — teste de limpeza do listener após falha.

**Validação:** os testes foram adicionados ao código, mas ainda dependem da execução da CI do commit final. Não afirmar sucesso de compilação/testes antes de a execução terminar. Nenhum teste foi executado no Poco C85 nesta rodada.

### Próximo passo

Aguardar CI. Se passar, avançar para instrumentação genérica e limitada de fluxo de controle (branches, J/JAL/JR/JALR, ERET, RA e A0–A3), com ring buffer e testes de neutralidade arquitetural. Só depois gerar APK e recolher um log novo no Android 16. Não integrar OSTask scheduler até localizar o envio real da tarefa pelo guest.

## 9. Estado atual resumido

| Área | Estado confirmado |
|---|---|
| Carregamento Z64/V64/N64 | Implementado e testado em unidade |
| Boot HLE | Parcial; sem IPL3/PIF original |
| R4300 CPU | Subconjunto; não completo |
| COP0/TLB | Parcial, com testes |
| RDRAM/MMIO | Parcial |
| PI/SP DMA | Síncrono/simplificado |
| RSP escalar/vetorial | Parcial |
| OSTask bootstrap/runner | Existe, explícito e isolado do fluxo principal |
| Scheduler de OSTask no runtime | Não implementado como dispatcher automático |
| DPC/RDP | Parcial; fill e triângulo plano RGBA16 |
| VI/framebuffer | Captura e apresentação implementadas; timing aproximado |
| Áudio | Não implementado |
| Joybus/controles | Não implementados completamente |
| Build/testes CI no HEAD auditado | Aprovados |
| Lint na CI | Não executado |
| Teste real no Poco C85 Android 16 para este HEAD | Pendente |
| GoldenEye jogável/renderizando | Não comprovado |

## 10. Próxima ação ao retomar

**Não começar reescrevendo CPU, TLB ou HLE.** Primeiro implementar a instrumentação genérica de fluxo de controle e snapshots limitados, sem mudar comportamento arquitetural. Depois gerar APK da branch `dev/n64-clean-start`, executar GoldenEye no Poco C85 Android 16, salvar o log e seguir a rotina real que antecede `0x7000071C`. Em paralelo, corrigir os dois problemas de tratamento de erro identificados (HALT forçado no RSP e listener DMA sem finally), cada um com teste de regressão.

---

**Última auditoria:** 2026-11-02  
**Branch:** `dev/n64-clean-start`  
**HEAD auditado inicialmente:** `d65a14b54ec626d7ba9a27b37963824f895807d7`  
**Última CI confirmada:** run `41434025556` — success  
**Alterações de código nesta auditoria:** nenhuma. Esta reconstrução substitui somente o conteúdo deste documento.

## 12. Expansão do RSP e RDP — segunda rodada

Branch: `dev/n64-clean-start`.

### RSP — novas operações vetoriais

Foram acrescentadas ao `N64RspVectorUnit` e decodificadas em `N64RspCore`:

- `VABS` (função COP2 `0x13`): seleciona o valor de VT pelo sinal da lane correspondente em VS; o resultado de 16 bits é saturado, incluindo o caso especial de magnitude de -32768.
- `VADDC` (`0x14`): soma lanes como valores sem sinal, grava os 16 bits inferiores e registra carry por lane em VCO low; limpa VCO high.
- `VSUBC` (`0x15`): subtrai lanes sem sinal, grava os 16 bits inferiores e registra borrow em VCO low e desigualdade em VCO high.

Testes adicionados:
- Carry por lane, wrap dos 16 bits e limpeza de VCO high em VADDC.
- Borrow e desigualdade por lane em VSUBC.
- Saturação de magnitude 32768 e seleção por sinal em VABS.
- Decodificação das três instruções pelo interpretador RSP.

### RDP — comandos de sincronização

O processador RDP síncrono agora reconhece `LOAD_SYNC` (0x26), `PIPE_SYNC` (0x27) e `TILE_SYNC` (0x28) como comandos sem efeito direto sobre pixels/estado de renderização neste modelo síncrono. Eles são aceitos pela validação de pacotes e contabilizados como comandos processados. `FULL_SYNC` (0x29) continua sendo contado pelo DPC separadamente, conforme o fluxo atual.

Isso não implementa sincronização temporal/FIFO real, interrupção DP do hardware nem o pipeline RDP completo. Também não implementa texturização, Z, coverage, blending ou combiner. Os comandos ainda sem execução continuam explicitamente rejeitados.

### Limites e validação

As alterações estão no código da branch e os testes de regressão foram escritos. A aprovação depende do workflow Android após o último commit. Ainda não houve teste no Poco C85 nem validação com ROM real nesta rodada.

### CI da rodada 12 — falhas e correções

O workflow falhou em 2 de 494 testes; a compilação do APK concluiu, mas `testDebugUnitTest` falhou. `N64RspCoreTest.decodesVaddcAndVsubcAndPreservesTheirCarryFlags` verificava VCO de VADDC depois de executar VSUBC, que substitui essas flags; o teste agora verifica cada instrução imediatamente após sua execução. `N64RspVectorUnitTest.absoluteUsesSignSourceAndSaturatesNegative32768Magnitude` esperava +12 para uma lane em que VS era positivo; VABS preserva VT nesse caso, portanto o valor correto é -12. Foram corrigidas as expectativas/ordem dos testes, sem alterar o núcleo para mascarar falhas. O novo CI precisa confirmar as correções.


## 13. HLE de sistema — primeira etapa (2026-11-02)

Foi criado `N64HleOsKernel.kt` na branch `dev/n64-clean-start`, com um modelo host-side inicial de threads, dispatcher cooperativo por prioridade/FIFO, yield/terminate, filas de mensagens limitadas FIFO e eventos com despertar de thread aguardando evento. Testes em `N64HleOsKernelTest.kt`.

**Limite:** ainda não é ABI guest de libultra. Não lê/escreve OSThread/OSMesgQueue na RDRAM, não salva/restaura contexto R4300, não intercepta chamadas da ROM, não integra interrupções MI/VI/AI/PI/SI/SP e não agenda OSTask. Não declarar que a ROM já usa esse kernel.

Documento detalhado: `docs/HLE_SISTEMA_PRIMEIRA_ETAPA_2026-11-02.md`.

Commits:
- `93b878de6f61457b860b4238e9d0fe0fa7c1e3ad` — modelo inicial do kernel HLE.
- `262b2e339d77a1225c1af121718ad0e29c78f203` — testes unitários.
- `3426bd5ed93a34168c454c1d3a2761c0e63b6d40` — documentação da etapa.

CI destes commits precisa ser consultada antes de declarar testes aprovados. Próximo passo técnico: verificar e corrigir semântica do modelo com CI; depois definir layouts guest de OSThread/OSMesgQueue e snapshot completo do contexto R4300 antes de ligar scheduler à execução da ROM.


## 14. Decisão: arquitetura HLE sem firmware externo (2026-11-02)

Direção confirmada: o fluxo normal do N64-Android não deve exigir arquivos externos de firmware/BIOS da Nintendo 64. Usar HLE no próprio emulador; não executar IPL3 nem depender de PIF ROM externa.

Importante: a ROM de cartucho ainda contém sua área IPL3. O código atual lê essa área somente para calcular checksum e reconhecer CIC; não copia nem executa IPL3. Atualmente reconhece apenas CIC 6102/7101 (seed 0x3F) e 6105/7105 (seed 0x91). Outros CICs ainda são rejeitados; não adivinhar seed.

Confirmado no código: `N64HleBoot.prepare()` já trabalha com `pifRom=null`, prepara 1 MiB em RDRAM, define parâmetros de boot e PC/stack. `N64HleBootTest` valida boot sem PIF ROM e que IPL3 não é carregado em SP DMEM. Isso não significa que PIF/Joybus HLE, ABI guest de libultra ou todos os CICs estejam implementados.

Próxima etapa recomendada: snapshot/restauração completo do contexto R4300 e TLB, com testes, antes de integrar OSThread guest e context switch. Depois implementar PIF/Joybus HLE e ampliar CIC coverage com dados verificados.

Documento: `docs/ARQUITETURA_HLE_SEM_FIRMWARE_2026-11-02.md`.

CI do HEAD anterior `6205cb66b87d33bec55889164393e5a72312a5e6`: workflow run `41520420713` — success. O commit de documentação desta seção ainda precisa de seu próprio workflow.


## 15. Expansão HLE + TLB (2026-11-02)

Na branch `dev/n64-clean-start`:
- `R4300Tlb`: adicionados snapshot de todos os slots e restore atômico com validação prévia; tradução detalhada reporta slot, tamanho de página, par/ímpar, ASID e Global. O método `translate()` permanece compatível.
- `N64HleOsKernel`: implementadas esperas reais no modelo host-side para envio/recebimento de mensagens; sender bloqueado guarda mensagem pendente, receiver bloqueado acorda na chegada, e FIFO de senders é respeitada.
- Testes adicionados para snapshot/restore, metadados de tradução, wakeup de send/receive e justiça FIFO.

Commits:
- `e4bafddaa84f8f123b08a31117ae8ae5ffd8706e` — snapshot e metadados TLB.
- `cf181127568ec1d8b2dc651d104b6054d383d273` — testes TLB.
- `18ab5965e65e3563869fd8fb00d53787b9054473` — operações bloqueantes HLE.
- `f460cfa7ad41dcfa8db5fcc18051af523e318c18` — testes HLE.

Documento: `docs/HLE_TLB_EXPANSAO_2026-11-02.md`.

Limite: TLB snapshot não é ainda contexto completo R4300; kernel permanece host-side e não intercepta ABI guest. CI desses commits precisa terminar antes de declarar aprovação.


## 16. Snapshot/restauração de contexto R4300 (2026-11-02)

Adicionados `R4300Cpu.snapshotContext()` e `restoreContext()` para capturar/restaurar GPR, HI/LO, COP0, FPR/FCR, TLB, TLB enable, PC, branch/delay slot pendente e estado interno Random/Wired. Restore valida estrutura/alinhamento antes de alterar estado, restaura TLB e limpa reserva LL/SC. Históricos diagnósticos não fazem parte do contexto.

Testes cobrem MULT (HI/LO), TLB, COP0/FPR/FCR e um J com delay slot pendente, além de rejeição de snapshots inválidos sem mutação.

Commits:
- `36c8fda2437107336344784efa6ae37dd8e12e35` — snapshot/restore do contexto R4300.
- `a69726cc6a93b7137e60b0825d6a9341f16e5128` — testes de contexto.
- `f8458bbe23c0c3d2a55a45b68869a347a278a97d` — ajuste da asserção de PC no teste.

CI da correção TLB `d0029fa8a96ed33ef412c2c53b4ee3cd00d60f4d`: success. CI dos commits de contexto ainda em execução na última consulta. Próximo passo: ligar o snapshot ao kernel HLE em um dispatcher explícito sem fingir que isso já é ABI guest; depois implementar layouts guest OSThread validados.


## 17. Expansão incremental HLE / RSP / RDP (2026-11-02)

### HLE + CPU R4300
- Adicionado `N64HleCpuScheduler`: registra contexto inicial por thread, salva contexto ativo e restaura o contexto da próxima thread em yield, bloqueio e término.
- Adicionados `startThread()` e `runQuantum(maxInstructions)`: execução cooperativa limitada por orçamento de instruções, sempre entre fronteiras de instrução. Não é preempção por timer.
- O scheduler exige contexto inicial explícito com PC igual ao entry point; ainda não constrói estruturas guest `OSThread`, stack ABI, nem intercepta chamadas libultra.
- Teste adicional executa instruções guest em quantum limitado e verifica registradores/PC.

### RSP
- Adicionados VMULF e VMULU. Ambos usam produto signed dobrado, bias 0x8000 e acumulador de 48 bits; VMULF satura o resultado em signed 16-bit e VMULU em unsigned 16-bit.
- Decoder COP2 vector despacha funct 0x00 para VMULF e 0x01 para VMULU.
- Testes cobrem arredondamento, acumulador, saturação negativa/positiva e decode de VMULU.
- Ainda faltam famílias VMUD/VMAC, comparações/seleção, muitas operações de memória e microcódigos gráficos completos.

### RDP
- Rasterizador de triângulo flat agora aceita color image RGBA16 e RGBA32.
- Para RGBA32 grava os 32 bits RGBA da primitive color; RGBA16 continua usando conversão RGBA5551.
- Mantidas limitações: sem textura, Z, coverage, blender, combiner, perspectiva e edge rules completos. Triângulo flat é subconjunto experimental, não compatibilidade geral.

### TLB
- O modelo já tinha 32 entradas, PageMask variável suportado, ASID/global, faults MISS/INVALID/MODIFIED, probe, metadados, snapshot/restore atômico.
- CPU já implementa TLBR, TLBWI, TLBWR e TLBP em subset COP0. A próxima ampliação deve validar semântica Random/Wired e testes de exceção/refill; não declarar MMU completa.

Commits desta etapa:
- `8fb8ae5` — VMULF vector unit.
- `0262eeb` — decode VMULF.
- `618dc92` — rasterizer RGBA32.
- `d705681` — dispatch RDP RGBA32.
- `45bfa43` — execução cooperativa HLE/CPU.
- `fea39d4` — teste triângulo RGBA32.
- `0cac748` — teste VMULF.
- `9debc82` — teste decode VMULF.
- `2dbfce7` — teste quantum HLE.
- CI do commit final desta etapa ainda precisa ser conferida.


## 18. VMULU no RSP (2026-11-02)
- Implementada a operação VMULU no vetor unit: multiplicação fracionária signed com acumulador de 48 bits e saturação unsigned do resultado para 0..65535.
- Decoder COP2 mapeia funct 0x01 para VMULU; VMULF permanece em funct 0x00.
- Testes unitários verificam resultado positivo, clamp de produto negativo para zero, acumulador e execução via decoder.
- Isso amplia o subconjunto aritmético, mas não implementa o restante do conjunto vetorial nem qualquer microcódigo completo.

## 10. Continuação em 2026-11-02 — contadores MMIO cumulativos

- HEAD anterior auditado pela continuidade: `d65a14b54ec626d7ba9a27b37963824f895807d7`. A branch avançou depois; consultar o HEAD remoto antes de novas alterações.
- Commit já existente na branch: `7f27ba0d4047fb7cbc4c2e04db00acab933ed96b` — documentação do suporte RSP VMULU; GitHub Actions #1371 passou e publicou o artifact `n64-android-debug-apk`.
- Correção diagnóstica em andamento: `N64Bus` já mantinha históricos MMIO limitados e contadores totais por dispositivo, mas o relatório automático calculava SP/DPC writes contando apenas entradas ainda presentes nos buffers. Isso subcontava sessões longas depois da evicção do histórico.
- Alteração aplicada na branch: novo mapa `diagnosticMmioWriteCounts`, com contagem cumulativa de escritas por dispositivo; `N64HleAutoDiagnostic` agora informa escritas SP/DPC acumuladas, sem depender dos buffers limitados.
- Testes adicionados: confirmar que 300 escritas SP e 300 DPC continuam contabilizadas mesmo com histórico de apenas 256 eventos; confirmar que o relatório usa o total de escritas.
- Commits desta correção: `ae2b0b351314fed364a484a450d4a4c17cdcfcf5`, `cd37631611dad022c5b364142bf381a566746d13`, `8061e828a7f88e975f36464d02f7d3d9a691c345`, `911a1db8dbc7c668093ed1d2c083c677bed8f303`. Verificar CI antes de considerar validado.
- O bloqueio funcional permanece: log anterior indicava CPU em loop idle `0x7000071C`, SP em HALT e nenhuma submissão SP/DPC observada. Não chamar `N64SpTaskRunner` com endereço presumido, não forçar HALT e não fabricar OSTask.
- Próxima etapa depois da CI: melhorar rastreamento do fluxo CPU que leva ao idle loop (chamadas/retornos/contexto de thread), usando evidência de execução; comparar os contadores cumulativos de MMIO no novo relatório. Depois disso, implementar somente a primeira correção comprovada.
- Estado desta etapa: alterações diagnósticas e testes enviados; compilação/CI dos commits acima ainda precisa ser confirmada. Nenhum APK novo desta correção deve ser anunciado até CI verde.

## 11. Expansão dos subsistemas HLE / TLB / RSP / RDP — 2026-11-02

Branch exclusiva: `dev/n64-clean-start`. A branch `main` não foi alterada.

### Alterações implementadas nesta etapa

- **HLE OS kernel:** adicionados `allThreadSnapshots()` e `diagnosticSnapshot()`, que expõem threads em ordem de ID, prioridades, estados, entry points, filas e quantidade de eventos sem disparar dispatch nem alterar estado. Teste de observabilidade adicionado.
- **TLB R4300:** adicionado `diagnosticSnapshot()` indexado por slot, exibindo entradas vazias/preenchidas, VPN2, ASID, PageMask, G, PFN, V e D. Teste correspondente adicionado.
- **RSP:** adicionado `ContextSnapshot` completo para salvar/restaurar GPRs, PC, HI/LO, branch delay pendente, estado vetorial (32 registradores, acumuladores e flags), contador de instruções e última instrução. A restauração valida alinhamento/faixa do PC e estado vetorial antes de aplicar o contexto; GPR0 é restaurado como zero e SP_PC é atualizado. Testes de round-trip e rejeição de PC inválido adicionados.
- **RDP:** adicionado snapshot de estado de processamento com color image, largura/formato, fill/primitive color, cycle type e scissor; diagnóstico textual não escreve na memória. Teste correspondente adicionado.
- **RSD:** não existe arquivo, classe, pacote ou documentação com esse identificador no repositório no HEAD consultado. Não foi criado um subsistema fictício. Solicitar ao usuário a expansão exata da sigla RSD antes de definir arquitetura ou código.

### Commits desta etapa

- RSP contexto: `9d2a6a6e668e29ce9480c9be429c0e9c081f019a`
- RSP testes: `9200e25fd6a648f01124c6959c0baf083406a082`
- TLB diagnóstico: `5c7eeba1234eab97b4312e0ea85654ba91e561fd`
- TLB testes: `21a60ba8be43f31915808454d9a9807e9315e532`
- RDP snapshot: `cf503cd1de10fde5b816f653a6cdff78b44faeea`
- RDP testes: `8646bcba78139178eb772a08b62b29f499a4a7e7`
- HLE diagnóstico: `6c7365513fbf1ca018be7a52bdafff0889b02b9f`
- HLE testes: `9521d165947a17f56c47b0572f3eb5cf9970a446`

### CI e limitações

- A correção anterior de comparações Long/Int no relatório HLE foi aprovada: Actions run `41528059913`.
- O teste do contexto RSP passou: Actions run `41528686538`.
- As execuções dos demais commits estavam em andamento no último levantamento. Não anunciar APK novo nem expansão funcional validada até confirmar CI verde do HEAD final.
- As alterações desta etapa ampliam estado de contexto e diagnóstico; não completam ainda o conjunto de microcódigos RSP, instruções TLB, comandos RDP ou ABI real de OSTask.
- O bloqueio funcional anterior permanece: CPU em loop idle `0x7000071C`, SP em HALT e sem submissão SP/DPC observada. Próxima investigação continua sendo o fluxo de inicialização/threads/filas da ROM, sem forçar SP ou fabricar tarefa.


## 12. Novas funções HLE / TLB / RSP / RDP — 2026-11-02

Branch: `dev/n64-clean-start`; `main` permanece intacta.

- **HLE:** `setThreadPriority(id, priority)` altera a prioridade da thread sem reiniciar seu estado/FIFO; `blockCurrentForEvent(type)` permite esperar por um tipo específico de evento. Eventos sem filtro continuam aceitos por qualquer tipo. Testes cobrem reordenação de dispatch e não despertar de thread por evento incompatível.
- **TLB:** `invalidateAsid(asid)` remove somente entradas não globais daquele ASID; `invalidateNonGlobal()` limpa entradas não globais e preserva mapeamentos globais. Teste cobre isolamento entre espaços de endereço e preservação global.
- **RSP:** adicionada operação vetorial `VSAR` (funct 0x1D) e método `accumulatorSlice`: element 8/9/10 seleciona as palavras HI/MD/LO do acumulador de 48 bits. Testes verificam a unidade e o decoder COP2.
- **RDP:** adicionado comando single-word `SET_BLEND_COLOR` (opcode 0x39); a cor de mistura é armazenada em estado, incluída em snapshot e diagnóstico. Isso não implementa ainda o blender/composição de pixels.
- Testes adicionais adicionados em `R4300TlbTest`, `N64RspVectorUnitTest`, `N64RspCoreTest`, `N64HleOsKernelTest` e `N64RdpFillProcessorTest`.

Commits funcionais desta etapa: `0d473fb`, `5e21a90`, `3544d3a`, `08590e1`, `e3e42d0`, `a11b96f`. Testes/correções: `fbb6ff3`, `34393f8`, `9bfd879`, `f6a785f`, `a450f72`, `ff06447`, `d36fa94`, `9fe5157`, `a1f77e6`.

CI final deve ser conferida no HEAD remoto antes de anunciar build verde ou APK. A compilação intermediária falhou por prioridade ainda declarada imutável e por teste RSP sem liberar SP HALT; ambas as causas foram corrigidas nos commits seguintes. Não tratar os runs intermediários como validação final.


## 19. CI verde e APK debug — correção HLE/TLB/RSP/RDP (2026-11-02)

- HEAD validado: `872e2d2d64d7ded2928ea791576b1d33b816b6cf` na branch `dev/n64-clean-start`.
- GitHub Actions run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41532494979 — **success**.
- A execução final validou `testDebugUnitTest` e `assembleDebug`; o artifact `n64-android-debug-apk` foi publicado (ID `13182588130`, tamanho 1.034.095 bytes, expira em 2026-11-16).
- O run anterior `41532488036` falhou porque foi disparado após o primeiro commit de correção e antes do commit seguinte que ajustou a expectativa VSAR. Não representa o HEAD final. No HEAD final, o workflow terminou com sucesso.
- Correção RDP: o teste agora constrói `SET_BLEND_COLOR` com o helper de comando, posicionando opcode e payload nos campos corretos.
- Correção RSP: expectativa da palavra LO de VSAR ajustada para refletir o valor real do acumulador VMULF (0x8000 no caso de teste).
- **Limite da validação:** CI verde comprova compilação e testes automatizados; não comprova instalação, boot de ROM, áudio, compatibilidade de jogo ou renderização no Poco C85 Android 16. O estado SET_BLEND_COLOR continua sendo armazenado, não aplicado por um blender gráfico real.
- Link do artifact/Actions: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41532494979

### Próxima etapa técnica

Priorizar rastreamento genérico e leve do fluxo de controle da CPU (J/JAL/JR/JALR, branches tomados, ERET, RA e A0–A3), com ring buffer limitado e testes de neutralidade arquitetural. Depois gerar log novo no Android 16 e identificar a rotina que deveria criar/enviar a OSTask. Não fabricar endereço de tarefa nem forçar SP_STATUS. Em paralelo, ampliar RSP/RDP somente a partir de comandos/instruções observados em ROM real.


## 20. Rastreamento genérico de fluxo R4300 — em validação (2026-11-02)

- Adicionado ao `R4300Cpu` um registro genérico e limitado de instruções de controle de fluxo: J, JAL, JR, JALR, BEQ/BNE/BLEZ/BGTZ, variantes likely, REGIMM de branch e BC1.
- Cada evento registra PC, instrução, decisão tomada/não tomada, destino efetivo ou fall-through, RA e argumentos A0–A3. O histórico reutiliza a lista limitada `diagnosticControlFlowHistory` (máximo 64 eventos); não altera registradores, PC, TLB ou decisões do guest.
- O caminho de aritmética/load-store não gera strings de diagnóstico. A detecção usa comparações simples, evitando criar conjuntos temporários por instrução.
- Testes adicionados para branch tomado, branch não tomado e JR indireto, incluindo destino e registradores RA/A0–A3.
- Commits: `43667e7` (instrumentação), `ddff364` (evitar alocações temporárias) e `1b1e77d` (testes).
- CI do HEAD `1b1e77da39cce9531a2908fd50396df84e43b91e` ainda pendente no momento da atualização. Não declarar esta etapa aprovada até o workflow terminar.

### Próximo passo

Consultar CI. Se passar, gerar APK debug e recolher log novo no Android 16 para identificar a sequência real de chamadas/retornos antes do loop idle. Se falhar, corrigir pela mensagem de teste/compilação. Ainda não há integração automática de OSTask nem prova de GoldenEye renderizando.


## 21. Correção do rastreamento genérico de fluxo R4300 — 2026-11-02

- A primeira execução da instrumentação genérica compilou, mas dois testes falharam porque as expressões Kotlin de interpolação foram escapadas como texto literal na montagem da linha diagnóstica.
- Corrigido em `R4300Cpu.kt`: interpolação real para PC, instrução, destino, RA e A0–A3.
- Commit da correção: `e6d30841ac7e1228e7a6887aadb97f653526d5f8`.
- A falha não foi nos opcodes BEQ/BNE/JR nem nas decisões de branch; os testes não conseguiam encontrar os campos formatados porque a string continha os marcadores literais.
- CI para este commit ainda precisa ser verificada. Não considerar a instrumentação validada até os testes e o APK passarem.


## 22. Auditoria do app e melhorias do fluxo de ROM — 2026-11-02

- Auditoria registrada em `docs/AUDITORIA_MELHORIAS_ANDROID16_2026-11-02.md`.
- `N64RomLoader.readAllBytes(InputStream, maxBytes)` centraliza leitura limitada, não confia no tamanho informado pelo provedor e trata leitura temporariamente retornando zero.
- `N64RomLoaderTest` ganhou testes de limite exato, excesso do limite e stream que retorna zero temporariamente.
- `MainActivity` agora lê ROM fora da thread principal, pede permissão persistente do Storage Access Framework, salva/restaura a última URI quando concedida e ignora resultados atrasados de seleções anteriores.
- Commits de implementação: `b434c20` (stream reader), `efb7938` (testes), `3ca2a4d` (Activity SAF/background restore), `b1ab065` (ordenação das seleções). A documentação de auditoria foi adicionada em `dc566d8`.
- CI desses commits deve ser verificada antes de declarar a rodada aprovada. Ainda falta teste instrumentado no Poco C85 Android 16.
- Limitações mantidas: scheduler OSTask não integrado automaticamente; RSP/RDP/DPC/TLB parciais; AI/áudio e Joybus/controles incompletos; MainActivity ainda precisa de extração incremental de responsabilidades.

## 23. Auditoria da execução R4300 — 2026-11-02

- Inspecionado o decoder/step da CPU, fluxo de PC/delay slot, exceções, acessos de memória e testes.
- Defeito confirmado em SD (opcode 0x3F): alinhamento de 8 bytes usava requireAligned com store=false implícito e produzia AdEL (4) em vez de AdES (5).
- Corrigido para requireAligned(address, 8, store = true).
- Teste sdUnalignedAddressRaisesStoreAddressError verifica ExcCode 5, BadVAddr=33, vetor de exceção e memória inalterada.
- SWR/SDR foram reavaliadas contra os testes exaustivos de offsets 0..3 e 0..7; sem defeito confirmado, sem alteração.
- Commits: e4407b6ee6dc6b64258734ba09277cbb6f249493 (correção) e 0a7b5f271c2f982e27023f5f6205305e0ead0b44 (teste).
- CI do HEAD 0a7b5f271c2f982e27023f5f6205305e0ead0b44: run 41537064446, ainda em andamento no momento do registro. Não anunciar APK aprovado antes de success.
- Limitações ainda abertas: COP1 aritmético/FPU, COP0 e exceções de barramento/cache incompletos; sem validação instrumentada de jogo no aparelho.

## 24. Auditoria COP1 e estado inicial HLE — 2026-11-02

- Encontrado bug: decoder executava COP1 mesmo com Status.CU1 (bit 29) desligado.
- Implementada exceção Coprocessor Unusable: ExcCode=11, Cause.CE=1; inclui COP1 e opcodes LWC1/LDC1/SWC1/SDC1, com verificação antes de acesso à memória.
- Testes adicionados para COP1 desabilitado, exceção em delay slot (BD/EPC), e opcode de memória COP1.
- COP1 branch/transfer tests habilitam CU1 explicitamente.
- Encontrado problema de integração: N64HleBoot.prepare salta IPL3 e não habilitava CU1 no estado inicial pós-IPL3.
- HLE agora habilita Status.CU1 antes do handoff; teste HLE executa MFC1 no entry point para confirmar.
- Commits: 8123f8b (exceção), c18078a/c249e69 (testes), a8b10a9 (cobertura de opcodes), 710b13d (HLE CU1), f529f3f (teste HLE).
- CI run 41538406114 para commit c249e6994a359a71450426d3f5ded47123f4efd9: success.
- CI do HEAD f529f3f6bfa13e3b8ba8bbd859a0648c97d5682e: aguardando resultado.
- Limitação atual: COP1 continua parcial; aritmética/conversões/comparações FPU, instruções de memória COP1 quando habilitado e semântica completa FCR31 ainda faltam.
- Não houve validação de jogo real no Android 16; não confundir CI com compatibilidade de jogos.


## 25. Auditoria do endereço 0x7000071C — 2026-11-02

- Interpretado como endereço virtual MIPS KUSEG, portanto depende da TLB; se traduzido para 0x0700071C, aponta para a janela física 64DD Domain 1 Address 1 (0x06000000–0x07FFFFFF).
- Bug encontrado em N64Bus: a janela Domain 2 Address 1 (0x05000000–0x05FFFFFF) estava coberta, mas Domain 1 Address 1 não; acessos podiam terminar em UnsupportedOperationException.
- Corrigido o mapeamento das duas janelas ausentes: leituras devolvem open bus (0xFF por byte) e gravações são ignoradas.
- Testes adicionados para byte/halfword/word e writes em 0x0700071C; também preservam cobertura de 0x05000508.
- Alterações de código/teste: commits 3fc7bc1a38f6404cc57df9749a8f00c3ba258aba e 21997bbc8a5c92f8b22b7092261c8674ac5b95d5.
- CI ainda precisa ser verificada; não declarar APK aprovado até success.
- Próximo passo: verificar CI, depois obter dump TLB/ASID/EntryHi/EntryLo do contexto real para confirmar a tradução efetiva de 0x7000071C. Não alterar main.


## 26. Auditoria de exceções de barramento R4300 — 2026-11-02

- Encontrado defeito: acessos físicos sem mapeamento em N64Bus lançavam UnsupportedOperationException até o worker, interrompendo a sessão sem exceção MIPS.
- R4300Cpu agora converte falha em FETCH para IBE (ExcCode 6) e falha em READ/WRITE para DBE (ExcCode 7), preservando diagnóstico e metadados de delay slot via enterException.
- Testes: unmappedDataReadKeepsTheFaultingInstructionInDiagnosticContext, unmappedInstructionFetchRaisesInstructionBusError e unmappedDataReadInBranchDelaySlotRaisesBusErrorWithBranchEpc.
- Commits: 78977193bf4a7a346767d787adf7d02e9e660461 (código), a0d72640ff07dcf3058c4708daeeb2ade5783117 (testes), 887f7630b8e1920b0637f62aeb6d3566ca5b60a4 (auditoria).
- Aguardar CI do HEAD; não há teste em aparelho nesta rodada. Não alterar main.


## 27. Auditoria adicional da execução R4300 — endereços físicos altos e temporizador COP0 (2026-11-02)

### Correção de falha no caminho de barramento
- A auditoria encontrou outro caminho de exceção de host: após a tradução TLB, endereços físicos acima de `0x7FFFFFFF` não cabem na API atual do barramento (`Int` assinado). O código anterior usava `require`, que lançava `IllegalArgumentException` e escapava do tratamento arquitetural de IBE/DBE.
- `R4300Cpu.physicalAddress()` agora lança `UnsupportedOperationException` para esse caso, que o `step()` já converte em IBE (FETCH, ExcCode 6) ou DBE (READ/WRITE, ExcCode 7). O diagnóstico mantém VA e access type, sem inventar PA.
- Testes adicionados para busca de instrução e leitura de dados com PFN alto, verificando Cause, EPC, vetor e diagnóstico.
- Commits: `bd32903a4f95c4aff9a3d62eb9004e70d08d367b` (correção) e `5ec2ee7f29d4048a393e804b361ac856076643d3` (testes).
- CI dos dois commits: success. Runs `41544150406` e `41544157494`; ambos executaram build/test e publicaram APK debug. Artifacts: `13187204623` e `13188615457`.
- Links: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41544150406 e https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41544157494.

### Limitação confirmada: COP0 Count/Compare e interrupção de timer
- A inspeção de `R4300Cpu.kt` não encontrou avanço do registrador Count (COP0 reg 9), comparação com Compare (reg 11), nem geração/limpeza automática de Cause.IP7 por timer. MFC0/MTC0 caem no armazenamento genérico do array COP0; isso não implementa temporização nem interrupção de timer.
- Impacto: software convidado que depende do timer R4300 pode ficar esperando, agendar incorretamente ou não receber interrupções esperadas. A interrupção MI/IP2 existente não substitui IP7.
- Não foi feita correção nesta rodada: o núcleo atual avança por instruções e os dispositivos têm temporização simplificada; implementar Count/Compare exige definir uma base temporal consistente e validar interação com Status.IM7, Cause.IP7, Compare write e scheduler. Simular timer por contagem arbitrária de instruções sem modelo temporal produziria falsa precisão.
- Próxima auditoria: estabelecer o modelo de ciclos/ticks do R4300 e adicionar testes para Count rollover, Compare match/clear, máscara IM7, IE/EXL/ERL e entrega de interrupção antes da instrução seguinte. Não declarar temporizador implementado até isso passar.

### Limites desta rodada
- CI verde valida compilação e testes automatizados, não execução real de ROM no Poco C85 Android 16.
- O APK debug está disponível nos artifacts dos dois runs acima; não foi instalado nem testado no aparelho nesta sessão.
- Branch de trabalho permanece `dev/n64-clean-start`; `main` não foi alterada.


## 28. Auditoria do relógio de execução e integração VI — 2026-11-02

### Resultado da inspeção do caminho de execução
- `N64RomVideoSession` chama `bus.advanceViInstructions()` no callback `afterInstruction`, uma vez por instrução executada. Portanto, o caminho contínuo de ROM/vídeo já avança o modelo aproximado do VI.
- `N64CpuExecutionSession` apenas delega `cpu.step()` a `N64ExecutionSession`; não avança o VI por conta própria.
- `N64HleCpuScheduler.runQuantum()` também chama somente `cpu.step()` e não avança relógio de dispositivos. Isso é uma lacuna se esse scheduler for usado como caminho autônomo de execução de ROM; não se deve duplicar o avanço no caminho `N64RomVideoSession`.
- O método `N64Bus.advanceViInstructions()` declara explicitamente um modelo aproximado de uma unidade por instrução, não ciclos reais por opcode. A integração atual serve como temporização grosseira de VI, não como relógio completo do console.
 
### COP0 Count/Compare — confirmação
- `R4300Cpu.step()` não atualiza Count (COP0 reg 9) após uma instrução.
- `MTC0 Compare` (reg 11) cai no armazenamento genérico e não limpa `Cause.IP7`; `MTC0 Count` (reg 9) também não tem semântica temporal específica.
- `serviceMiInterrupt()` amostra a linha MI como IP2, mas não sintetiza nem entrega IP7 de timer.
- Não corrigir Count usando simplesmente o contador de instruções: o VI atual já é explicitamente aproximado e não fornece ciclos R4300 por opcode. Definir um domínio temporal compartilhado é pré-requisito para implementar Count/Compare com comportamento verificável.

### Próximas ações seguras
1. Mapear todos os caminhos de execução realmente usados pela Activity e decidir se `N64HleCpuScheduler` será integrado ao loop contínuo ou permanecerá como scheduler cooperativo isolado.
2. Se o scheduler for usado para executar ROM, injetar uma interface de avanço de dispositivos para evitar relógios divergentes e dupla contagem.
3. Projetar um acumulador de ciclos R4300 (por opcode/estágio) e só então derivar Count/Compare e o avanço VI do mesmo relógio.
4. Testar rollover de Count, match Compare, limpeza de IP7 ao escrever Compare, máscara IM7, IE/EXL/ERL e prioridade/entrega antes da instrução seguinte.

### Estado
- Nenhum código funcional foi alterado nesta auditoria; não há alegação de que timer COP0 esteja implementado.
- CI #1468 para o commit `944298cbbbb2c31515610b27c14682050378b2d4` passou.
- Branch `dev/n64-clean-start`; `main` intacta. Ainda não houve teste de ROM no aparelho nesta rodada.


## 29. Auditoria da cobertura de instruções COP1 e acessos FPU — 2026-11-02

### Resultado confirmado
- `R4300Cpu.stepInstruction()` valida `Status.CU1` antes de instruções COP1 e das opcodes de memória COP1 (`LWC1` 0x31, `LDC1` 0x35, `SWC1` 0x39, `SDC1` 0x3D). Com CU1 desligado, o trap Coprocessor Unusable (CE=1) é esperado.
- Com CU1 ligado, o `when(opcode)` não possui handlers para 0x31, 0x35, 0x39 ou 0x3D. Essas instruções chegam ao `else` final e geram Reserved Instruction (RI), em vez de acessar os registradores FPU/memória.
- O handler COP1 (opcode 0x11) atualmente implementa transferências MFC1/CFC1/MTC1/CTC1 e branches BC1; o próprio código identifica a aritmética FPU como ainda não implementada.
- O HLE boot habilita CU1, portanto ROMs que executem loads/stores COP1 podem alcançar RI em runtime. A ausência de FPU arithmetic é uma limitação adicional de compatibilidade, mesmo que os acessos de memória sejam implementados.
- O teste existente `cop1MemoryInstructionAlsoRequiresCu1` cobre apenas o caso CU1 desabilitado; ele não comprova a execução dos quatro acessos quando CU1 está habilitado.

### Próxima implementação sugerida
1. Antes de codificar, confirmar o modelo de FPR (FR=0/FR=1, pareamento de registradores de 32 bits e endianness) e o tratamento de FCR31.
2. Implementar LWC1/LDC1/SWC1/SDC1 com tradução TLB, exceções AdEL/AdES/IBE/DBE e preservação correta de bits.
3. Criar testes de loads/stores em big-endian, alinhamento, TLB miss/invalid/dirty, CU1 disabled/enabled e delay slot.
4. Só depois avançar para operações aritméticas COP1 e flags/causas FPU; não declarar suporte FPU completo após implementar somente transferências de memória.

### Estado desta rodada
- Auditoria estática confirmou a lacuna; nenhum código funcional foi alterado e nenhum suporte FPU novo foi declarado.
- O CI #1469 do commit de documentação anterior `479bf6204fc22de1be1697932132ba94df4c51b9` terminou com success: https://github.com/jovemaprendiz55-byte/N64-Android/actions/runs/41545628743.
- Próxima atualização de documentação/código deverá continuar na branch `dev/n64-clean-start`; `main` permanece intacta.


## 30. Auditoria adicional do estado FPU e diagnóstico de memória — 2026-11-02

### Achados confirmados na inspeção do código
- O banco `fpr` tem 32 elementos do tipo `Long`, mas os comentários e as transferências MFC1/MTC1 tratam cada elemento como palavra bruta de 32 bits. Não há implementação visível do modo FR do Status nem do pareamento de registradores necessário para operações double no modelo atual.
- `LDC1`/`SDC1` não podem ser implementados com segurança apenas lendo/escrevendo um `Long` por FPR sem primeiro definir FR=0/FR=1, regras de pares e endianness do VR4300.
- `CTC1` grava o valor de 32 bits com extensão de sinal no array `fcr`; não foi encontrada validação/máscara específica de bits graváveis de cada FCR. `BC1` consulta somente FCC0 (bit 23 de FCR31), e o código rejeita os outros condition codes.
- A montagem de `diagnosticEffectiveAddress` em `R4300Cpu.stepInstruction()` não inclui os opcodes 0x31/0x35/0x39/0x3D. Mesmo após adicionar handlers, o contexto de diagnóstico do barramento não registrará a EA desses acessos enquanto essa lista não for ampliada.
- O CI #1470, run `41546164519`, do commit `610e8110590e96867dc8704779f0ad3f9fd825c6`, terminou com success. Isso valida o estado documentado e os testes existentes, não prova execução dos opcodes COP1 ausentes nem compatibilidade de ROM.

### Próximas ações seguras
1. Especificar FR=0/FR=1 e a representação física/lógica dos FPRs antes de mexer em LDC1/SDC1.
2. Definir quais FCRs são implementados, bits graváveis e atualização de FCR31 (flags, causes, enables e FCC).
3. Acrescentar os quatro opcodes COP1 de memória ao cálculo de EA de diagnóstico.
4. Implementar cada acesso com teste de alinhamento, tradução TLB, exceção e ausência de efeitos parciais em falhas.
5. Não declarar FPU completa até aritmética, conversões, comparações, exceções e condições estarem cobertas.

### Estado
- Esta etapa foi auditoria estática e documentação; nenhum código funcional foi alterado.
- Branch `dev/n64-clean-start`; `main` permanece intacta.
- Não houve instalação nem teste de ROM no Poco C85 nesta etapa.


## 31. Auditoria do contexto de diagnóstico da CPU — 2026-11-02

### CI anterior
- CI #1471 do commit `ba5924d6a50dac627da69c5fdd881e0c28441ecc` terminou com success: build/teste automatizado concluído.
- Isso não valida instruções COP1 que continuam sem handler e não equivale a teste de ROM no aparelho.

### Defeito encontrado
- Em `R4300Cpu.stepInstruction()`, o cálculo de `diagnosticEffectiveAddress` contempla vários loads/stores inteiros, mas omite explicitamente os opcodes COP1 de memória: LWC1 (0x31), LDC1 (0x35), SWC1 (0x39) e SDC1 (0x3D).
- `N64Bus.setDiagnosticCpuInstructionContext()` armazena essa EA para compor o contexto das gravações de PTE. Portanto, se os handlers COP1 forem adicionados sem corrigir essa lista, os logs poderão mostrar PC/instrução/registradores-base, mas não a EA calculada para essas operações.
- Este é um defeito de observabilidade/diagnóstico, não a causa direta do RI atual: os quatro opcodes ainda não têm handlers quando CU1 está habilitado.

### Limitações funcionais rechecadas
- O array FPR é explicitamente descrito no código como armazenamento de palavras brutas de 32 bits com semântica double pareada incompleta.
- MTC1/MFC1 transferem palavras brutas de 32 bits; isso não constitui implementação de LDC1/SDC1 nem de aritmética FPU.
- CTC1 grava FCR sem máscara específica por registrador; BC1 só aceita FCC0. O conjunto de controle FPU permanece parcial.

### Próximo passo
1. Ao implementar LWC1/LDC1/SWC1/SDC1, incluir esses quatro opcodes na EA de diagnóstico.
2. Acrescentar teste de integração que confirme a EA observada para instruções COP1 de memória, além dos testes arquiteturais de TLB, alinhamento e exceções.
3. Projetar FR/FPR e FCR31 antes de implementar operações de 64 bits ou declarar suporte FPU.

### Estado
- Esta rodada foi inspeção estática e atualização da documentação; nenhum código funcional foi alterado.
- Commit de documentação na branch `dev/n64-clean-start`; `main` intacta.
- Não houve execução de ROM nem teste no Poco C85 nesta rodada.

## 32. Auditoria da execução — contexto de diagnóstico das operações COP1 de memória

- CI anterior #1472 (run `41546789564`) confirmado como `success`; isso valida build/testes automatizados daquele commit, não execução de ROM no aparelho.
- No `R4300Cpu.stepInstruction()`, a lista usada para calcular `diagnosticEffectiveAddress` incluía stores inteiros, mas omitia LWC1/LDC1/SWC1/SDC1 (opcodes `0x31`, `0x35`, `0x39`, `0x3D`).
- Correção aplicada: os quatro opcodes COP1 de memória agora entram no cálculo diagnóstico da EA como `GPR[rs] + sign-extended immediate`. Isso melhora o contexto enviado ao barramento/watchpoint quando esses handlers forem executados.
- Limite importante: os handlers funcionais LWC1/LDC1/SWC1/SDC1 continuam ausentes; com CU1 habilitado, essas instruções ainda geram RI. Esta correção **não** implementa FPU nem faz jogos que dependem dela funcionarem.
- O caminho real `N64RomVideoSession → R4300Cpu.step()` foi confirmado; ele avança VI por aproximação e consulta o RSP em quantum cooperativo. Ainda não há execução automática de OSTask no fluxo principal, nem temporização de hardware fiel.
- Nenhum teste em aparelho/ROM foi realizado nesta rodada. Validar via CI após commit.

## 33. Auditoria da busca de instrução — salto para endereço desalinhado

- Defeito confirmado: `R4300Cpu.stepInstruction()` buscava a instrução com `memory.read32(physicalAddress(...))` sem validar previamente o alinhamento virtual de 4 bytes.
- Um `JR`/`JALR` com alvo não alinhado podia deixar a exceção de alinhamento da implementação de memória escapar como exceção host, em vez de entregar AdEL (ExcCode 4) e atualizar BadVAddr/EPC.
- Correção aplicada: `requireAligned(instructionAddress, 4)` agora é executado antes da tradução/busca; usa o caminho já existente de `AddressFault` para AdEL.
- Teste adicionado: `unalignedIndirectJumpTargetRaisesAddressErrorInsteadOfHostCrash`; executa JR, delay slot e busca no endereço `0x21`, verificando ExcCode, BadVAddr, EPC, BD=0 e vetor de exceção.
- Validar o commit por CI antes de considerar a correção aprovada. Não houve teste de ROM nem instalação no Poco C85 nesta rodada.


## 34. Auditoria do caminho de memória R4300 — 2026-11-02

### Verificações realizadas
- Conferidos os caminhos de LB/LBU, LH/LHU, LW/LWU, LD, LL/LLD, SB/SH/SW/SD e as operações de merge LWL/LWR, SWL/SWR, LDL/LDR, SDL/SDR.
- Loads/stores alinhados de 2, 4 e 8 bytes validam o alinhamento virtual antes da tradução física; os stores passam o indicador de escrita para gerar AdES (ExcCode 5), e os loads geram AdEL (ExcCode 4).
- A tradução TLB recebe explicitamente o tipo READ ou WRITE, permitindo distinguir TLB miss/invalid de leitura e escrita e Modified em escrita.
- Falhas de barramento físico não mapeado são convertidas pelo caminho de execução em DBE/IBE, enquanto falhas TLB e de alinhamento têm handlers arquiteturais próprios.
- As operações de merge trabalham sobre endereço virtual alinhado à palavra/doubleword antes da tradução. Não foi encontrada nesta passagem uma falha reproduzível que justificasse alterar essas fórmulas sem testes adicionais.
- Os acessos de 64 bits são divididos em transferências de 32 bits big-endian; o alinhamento de 8 bytes mantém os dois segmentos dentro da mesma página TLB normal, mas a temporização e a atomicidade de dispositivos ainda não são modeladas como hardware real.

### Resultado e limitações
- Nenhum defeito funcional novo foi confirmado nesta passagem; portanto, nenhum handler de memória foi alterado especulativamente.
- A FPU continua incompleta: LWC1/LDC1/SWC1/SDC1 e operações aritméticas COP1 permanecem ausentes quando CU1 está habilitado, conforme documentado nas seções anteriores.
- A auditoria foi estática, com inspeção do código e dos testes existentes. Não equivale a execução de ROM nem a teste no Poco C85.
- CI #1475, #1476 e #1477 terminou com success, cobrindo os commits da correção de fetch desalinhado e sua documentação.
- Branch dev/n64-clean-start; main permanece intacta.


## 35. Auditoria RSP / telemetria MMIO — 2026-11-02

- CI #1478 confirmou success para o registro da auditoria de memória.
- Encontrado acesso de telemetria indevido: N64RspCore.halted lia SP_STATUS_REG por bus.read32() em cada verificação interna de HALT, registrando leituras artificiais no histórico de MMIO e inflando os contadores de acessos SP.
- Corrigido o getter halted para usar N64Bus.diagnosticSpStatus(), consulta interna side-effect-free já existente no barramento. Leituras reais do registrador feitas pela CPU continuam registradas normalmente.
- Adicionado teste internalHaltPollingDoesNotCreateSyntheticSpMmioReads, que executa instruções até BREAK e verifica que a sondagem interna não acrescenta leituras SP ao histórico.
- A correção reduz ruído e trabalho de instrumentação; não altera estado de hardware guest nem implementa novos opcodes RSP.
- RSP continua sendo um subconjunto MIPS-I/COP2 e RDP suporta somente os comandos explicitamente implementados; microcódigo gráfico comercial não está certificado.


## 36. Auditoria da integração DPC/RDP com exceções da CPU — 2026-11-02

### Defeito confirmado
- O caminho de execução da ROM escreve DPC_END por meio do barramento dentro de uma instrução de store da CPU.
- Quando a display list continha um comando RDP multiword conhecido, mas ainda não implementado (por exemplo, TextureRectangle/opcode 0x24), N64DpcCommandProcessor lançava UnsupportedOperationException.
- N64Bus.write32(DPC_END) propagava essa exceção host. Como o store ocorre dentro de R4300Cpu.step(), o caminho de barramento podia interpretar uma limitação do emulador como falha física de escrita e injetar DBE (ou interromper a sessão), embora o endereço MMIO fosse válido.
- Isso misturava duas classes de falha: acesso físico inválido do guest e comando gráfico válido cuja execução ainda não é suportada pelo emulador.

### Correção aplicada
- N64Bus.write32(DPC_END) agora captura especificamente UnsupportedOperationException, registra a mensagem em diagnosticLastDpcFailure e retorna sem fabricar uma exceção de barramento para a CPU.
- Falhas de validação de endereço/range e outras exceções continuam seguindo o caminho anterior; não foram silenciadas genericamente.
- Em falha de comando, DPC_CURRENT permanece no início da lista e não é gerado DP interrupt.
- Teste unsupportedRdpCommandIsDiagnosticAndDoesNotBecomeCpuBusFault: envia um TextureRectangle de 16 bytes, confirma ausência de exceção host, diagnóstico de UnsupportedOperationException, CURRENT preservado e ausência de MI interrupt.
- O teste direto de N64DpcCommandProcessor continua podendo observar a exceção de comando não suportado; a alteração é no limite entre dispositivo e CPU.

### Limites
- A correção não implementa TextureRectangle, texturização, combiner, Z-buffer, cobertura ou microcódigo RSP comercial.
- A sessão ainda pode não produzir pixels para ROMs que dependem desses comandos; a limitação fica visível no diagnóstico DPC, sem se transformar em falso DBE.
- Validar por CI. Não houve instalação nem teste de ROM no Poco C85 nesta rodada.
- Branch dev/n64-clean-start; main não foi alterada.


## 37. Auditoria de captura VI durante execução contínua — 2026-11-02

### Defeito confirmado
- N64RomVideoSession chamava N64Bus.captureViFrameFromRegisters() diretamente no callback afterInstruction da sessão CPU.
- Os registradores VI são escritos pelo software guest e podem ficar temporariamente inconsistentes durante a inicialização ou troca de modo de vídeo.
- O leitor de framebuffer valida pixel size, stride e limites de RDRAM com IllegalArgumentException. Antes da correção, uma configuração temporariamente inválida escapava do callback e podia colocar o worker de execução da CPU em FAILED, mesmo sem exceção arquitetural do R4300.

### Correção aplicada
- A captura periódica agora captura apenas IllegalArgumentException da validação de framebuffer; registra a mensagem em lastFrameCaptureFailure e continua a execução da CPU sem publicar um frame inválido.
- Uma captura válida subsequente limpa o diagnóstico anterior.
- Exceções de onFrame e falhas não relacionadas à validação do framebuffer não são engolidas por esse catch.
- MainActivity exibe e exporta o último erro de configuração VI no painel/logs.
- Teste invalidTransientViPixelSizeDoesNotFailCpuExecution configura geometria válida com VI_STATUS pixel size 0, executa oito instruções reais do R4300 e verifica sessão COMPLETED, nenhum frame e diagnóstico VI presente.

### Limites e validação
- A correção não altera a interpretação dos formatos RGBA5551/RGBA8888 nem implementa scanout temporizado.
- A captura ainda é amostrada por intervalo de instruções; VI timing segue aproximado.
- Aguardar CI para validar os commits de sessão, teste, UI e documentação. Nenhum teste real de ROM foi executado no aparelho nesta rodada.
- Branch dev/n64-clean-start; main permanece intacta.


## 38. Auditoria de publicação de quadros: mudança de resolução (2026-11-02)

- Defeito confirmado por inspeção: `N64RomVideoSession` deduplicava quadros comparando somente `argbPixels`. A geometria do quadro não fazia parte da condição.
- Consequência: uma mudança de resolução com o mesmo número de pixels e conteúdo ARGB idêntico podia ser descartada. O presenter Android continuaria exibindo o bitmap com dimensões antigas, embora o VI já tivesse mudado de modo.
- Correção em `N64RomVideoSession.kt`: publicar quando largura ou altura mudar, mesmo que o vetor de pixels seja idêntico. A comparação de pixels permanece para evitar publicações redundantes quando geometria e imagem não mudam.
- Teste de regressão `publishesResolutionChangeEvenWhenPixelContentsAreIdentical`: executa instruções R4300 reais, captura 2x2, altera os registradores VI para 4x1 no meio da execução, mantém os mesmos quatro pixels e confirma que ambos os quadros são publicados.
- Escopo: corrige detecção de mudança de geometria no caminho VI → frame callback. Não implementa novos formatos de pixel, rasterização RDP, microcódigo RSP ou temporização de ciclo.
- Ainda não houve instalação nem teste visual no Poco C85. A confirmação desta correção depende do CI e, para validação final da tela, de teste no aparelho.


## 39. Correção do teste de mudança de resolução VI — 2026-11-02

- A CI do commit `13f5c3e` compilou o APK, mas falhou em 1 de 552 testes: `N64RomVideoSessionTest.publishesResolutionChangeEvenWhenPixelContentsAreIdentical`.
- A falha estava na fixture do teste, não no caminho de publicação: o teste preenchia somente os quatro primeiros pixels físicos. Ao alterar a janela VI de 2x2 para 4x1, a amostragem geométrica podia ler posições diferentes do framebuffer, então a asserção de igualdade de ARGB não era válida.
- Corrigido o teste para preencher uma região ampla de RDRAM com a mesma cor RGBA5551, garantindo conteúdo visual idêntico sob as duas geometrias.
- Código de produção `N64RomVideoSession.kt` não foi alterado nesta correção; a condição de publicação continua incluindo largura, altura e conteúdo ARGB.
- Commit do ajuste: `ce9ba4181aecc26c21bda02a3379a38ee8f0d4d9`. Aguardar CI desse commit. Não declarar aprovação nem distribuir APK antes de todos os testes passarem.
- Branch `dev/n64-clean-start`; `main` permanece intacta. Sem teste visual no aparelho nesta etapa.


## 40. Lacuna COP0 Count/Compare — 2026-11-02

- Auditoria estática confirmou ausência de incremento de COP0 Count (reg. 9), comparação com Compare (reg. 11) e geração de Cause.IP7 (bit 15) para interrupção interna do temporizador R4300.
- Na auditoria seguinte, foi corrigida a escrita MTC0 Compare para limpar Cause.IP7; a geração de IP7 pelo temporizador ainda não existe. A rotina de amostragem ainda espelha MI em IP2.
- Impacto: software que depende do timer interno para scheduler/timeouts pode travar ou executar com temporização incorreta.
- Não adicionar incremento por instrução sem definir antes a fonte de ticks e divisor do Count do VR4300. O emulador ainda não modela ciclos/stalls de forma suficiente para uma implementação temporal confiável.
- Planejamento: camada de ticks determinística; política Count/ticks documentada; MTC0 Compare limpa IP7; igualdade Count/Compare levanta IP7; testes para IE/IM7/EXL/ERL, delay slot e limpeza por Compare.
- Nenhum código de produção alterado nesta etapa; achado e plano registrados em `docs/AUDITORIA_MELHORIAS_ANDROID16_2026-11-02.md`. Branch dev/n64-clean-start; main intacta.


## 41. CI verde e APK Debug gerado — 2026-11-03

- A execução GitHub Actions `41559840127`, para o commit `a445bd556cbb5f360b08a40bccf73e854a4ad900`, concluiu com `success`.
- Job `build`: setup, checkout, JDK 17, Gradle, Build and test e Upload debug APK concluídos com sucesso.
- Artefato `n64-android-debug-apk` disponível, tamanho 1.039.850 bytes, SHA-256 `91c6f00c10d5f7eaa70b5b2c141ce4a73218cfafde9ddbe07c68bc30dceca439`, expira em 2026-11-17.
- A aprovação confirma build e testes automatizados deste commit; não substitui instalação/validação visual no aparelho Android 16.
- A correção do fixture VI foi aprovada. O achado COP0 Count/Compare continua aberto e documentado; não foi implementado.
- Nota: a CI também concluiu success no commit de documentação `b8378acd` e no commit de correção `ce9ba418`.
- Branch `dev/n64-clean-start`; branch `main` continua separada e inalterada por estas alterações.


## 42. Correção parcial do COP0 Compare — 2026-11-03

- Auditoria confirmou que MTC0 no registrador COP0 11 (Compare) caía no armazenamento genérico e deixava Cause.IP7 (bit 15) pendente.
- Correção em `R4300Cpu.kt`: MTC0 Compare agora grava o valor de 32 bits e limpa somente Cause.IP7, preservando IP2 e os bits de interrupção de software IP[1:0].
- Teste de regressão `mtc0CompareStoresValueAndClearsOnlyTimerInterruptPendingBit`: executa uma instrução MTC0 real e valida Compare, limpeza seletiva de IP7, preservação de IP2/IP[1:0] e avanço do PC.
- Commits de código/teste: `17ef9e8ba1a594ac4dfa2f2ceb902af68d8824fb` e `de6cd1f29732a90c14e3ccc1c91800d2a85d946f`.
- Limitação ainda aberta: Count não avança e não há comparação Count==Compare gerando IP7. Não há política de ticks/ciclos do VR4300; não foi introduzido incremento artificial por instrução.
- Aguardar CI do commit de teste. Nenhuma instalação nem execução real de ROM no aparelho foi realizada nesta etapa.
- Branch `dev/n64-clean-start`; `main` não foi alterada.

## 43. Auditoria do limite de cancelamento da sessão de execução — 2026-11-03

### Defeito confirmado
- N64ExecutionSession.execute() verificava stopRequested no início do loop interno, mas chamava beforeInstruction e iniciava stepInstruction sem verificar novamente a flag.
- Se o callback ou outra thread solicitasse parada durante beforeInstruction, uma instrução guest adicional ainda começava depois do pedido de cancelamento, embora a instrução ainda não tivesse sido despachada.
- Isso criava uma janela entre o ponto de verificação e o início da instrução, contrariando a semântica pretendida de cancelamento cooperativo entre instruções.

### Correção e regressão
- Adicionada uma segunda verificação de stopRequested imediatamente após beforeInstruction e antes de stepInstruction.
- Se a parada chegar nesse intervalo, o lote é encerrado sem executar ou contabilizar outra instrução; o estado final permanece CANCELLED.
- Novo teste cancellationFromBeforeInstructionDoesNotExecuteNextInstruction: o callback solicita parada antes do despacho e o teste confirma zero instruções executadas/contabilizadas e estado CANCELLED.
- A verificação existente cancellationStopsBetweenInstructions permanece cobrindo parada solicitada durante a instrução atual, que pode terminar antes de a sessão parar.

### Escopo e validação
- A alteração afeta somente o agendamento/cancelamento cooperativo da sessão; não altera instruções R4300, estado guest, MMIO, VI ou temporização de hardware.
- Código e teste foram enviados à branch dev/n64-clean-start; aguardar CI para confirmar compilação e testes.
- Auditoria COP0 Count/Compare continua aberta: Count ainda não avança e não há geração automática de Cause.IP7 por igualdade Count/Compare. Não inventar ticks por instrução.
- Nenhuma execução visual de ROM nem instalação no Android 16 foi realizada nesta rodada.
- Branch main permanece intacta.
## 44. Primeira implementação COP0 Count/Compare e transferências COP1 — 2026-11-03

### COP0 Count/Compare
- R4300Cpu.kt: foi adicionado avanço determinístico de Count (COP0 reg. 9), com divisor de 2 ciclos por tick, registrador de 32 bits e wraparound.
- advanceCountCycles(cpuCycles) permite avançar a base de ciclos explicitamente; quando Count incrementa e alcança Compare (reg. 11), Cause.IP7 (bit 15) é levantado.
- MTC0 Count escreve os 32 bits baixos e reinicia a fase do divisor. A escrita em Compare continua limpando somente IP7.
- Limitação importante: o interpretador ainda não possui tabela real de latência/stalls por instrução. Por enquanto, cada chamada de step() fornece dois ciclos-base, isto é, um tick de Count por instrução executada. A API de avanço explícito permite substituir essa aproximação quando o scheduler/modelo temporal passar a fornecer ciclos reais. Não considerar esta etapa timing cycle-accurate.

### Transferências de memória COP1
- Implementados LWC1 (0x31), LDC1 (0x35), SWC1 (0x39) e SDC1 (0x3D).
- Operações usam ordem big-endian, tradução virtual/física do R4300, validação de alinhamento e os caminhos existentes de exceção AdEL/AdES e TLB.
- LWC1/SWC1 manipulam os 32 bits baixos do slot FPR; LDC1/SDC1 manipulam o slot de 64 bits. A semântica completa FR=0/pairing do VR4300 ainda não está modelada.
- O bloqueio CU1 continua sendo verificado antes de tradução/acesso à memória, preservando Coprocessor Unusable quando FPU está desabilitada.
- Testes novos em R4300Cop1MemoryTest.kt: round-trip de palavra, round-trip de doubleword e desalinhamento LDC1 sem alteração do FPR.

### FPU aritmética
- Ainda pendente nesta rodada. Operações aritméticas S/D, conversões, comparações, flags/rounding e exceções IEEE/FCR31 não foram implementadas. Não anunciar a FPU como completa.

### Commits
- b08ee03d6e7bd7a425a6b3e706bc1f6ee3ec4c93 — transferências de memória COP1.
- 8c1dda2ad881e313b0b564bf07cdf79a8ac81235 — testes de transferências COP1.
- ce4dfc4ea7dc84a0b2bc28d8708ae6b3f743911b — avanço Count e timer IP7.
- 478d760d8b77b97949627418cd4259447bd6905f — testes de Count/timer.
- GitHub Actions run 41562510509 iniciou para o commit de testes; resultado ainda deve ser consultado antes de declarar aprovação.
- Nenhum teste visual de ROM nem instalação no Android 16 foi realizado nesta etapa.
- Branch dev/n64-clean-start; main permanece intacta.
### Continuação FPU aritmética — 2026-11-03
- Implementado subconjunto aritmético COP1 para formatos S e D: ADD, SUB, MUL, DIV, SQRT, ABS, MOV e NEG.
- Valores são interpretados/gravados como bits IEEE-754 nos slots FPR; S grava os 32 bits baixos e D grava os 64 bits.
- Testes novos em R4300Cop1ArithmeticTest.kt cobrem soma/multiplicação single, subtração/divisão double e exceção Coprocessor Unusable quando CU1 está desligado.
- Limitações: conversões W/L, comparações C.cond, FCC adicionais, modos de arredondamento, flags Cause/Enable/Flag do FCR31, traps IEEE e semântica completa FR=0/FR=1 ainda não estão implementados. O conjunto aritmético atual é parcial.
- Commit de produção: 1b05676f663f503bc0950986f4fab9a43efd557a.
- Commit dos testes: d9eb615674d52ce62c23121ff3f529c7c194bf38.

## 45. Auditoria R4300 — Count durante entrada de interrupção MI — 2026-11-03

### Defeito confirmado e corrigido
- Em `R4300Cpu.step()`, `serviceMiInterrupt()` podia entrar na exceção e retornar antecipadamente antes de `advanceCountCycles(2)`.
- Consequência: o relógio COP0 Count ficava parado justamente nos passos em que a CPU aceitava uma interrupção MI. Isso tornava o timer dependente do tipo de passo executado.
- Correção: o caminho de entrada MI agora avança os mesmos dois ciclos-base antes do retorno. O passo de interrupção não executa uma instrução guest, mas não congela Count.
- A aproximação temporal continua deliberadamente não cycle-accurate: dois ciclos-base por chamada de step; latências reais/stalls ainda não estão modelados.

### Testes adicionados
- `countAdvancesAtHalfRateAndCompareMatchSetsTimerPending`: valida divisor de dois ciclos, igualdade Count/Compare, IP7 e wraparound de 32 bits.
- `countContinuesAdvancingWhenMiInterruptIsTaken`: configura uma fonte MI real no N64Bus, habilita IE/IM2 e confirma que Count avança quando a interrupção é aceita, com IP2 e EXL corretos.

### Auditoria estática adicional — limitações encontradas, sem correção especulativa
- COP1 continua parcial: somente transferências e um subconjunto de operações S/D estão implementados. FR=0/FR=1, pares FPR, conversões, comparações, FCC adicionais, arredondamento e exceções IEEE/FCR31 continuam pendentes.
- COP0/TLB e semântica de delay slot permanecem subconjuntos, conforme o cabeçalho de R4300Cpu.kt. A presença de testes não significa cobertura completa do manual VR4300.
- Count ainda usa dois ciclos-base por step e não mede latência real de instruções, stalls ou dispositivos.
- A implementação LDC1/LD de doubleword traduz as duas metades separadamente; comportamento em fronteira de página e atomicidade de acesso ainda merecem teste específico.
- Não foi alterada a semântica de FPU/TLB/delay slot sem reprodução confiável.

### Commits desta rodada
- `edacdbd313120f0ae5fb076d52297ea305f5f1c4` — correção de Count na entrada MI.
- `0c6e4fd91747b86abbabb627a678d945adbf7644` — testes de Count/Compare e interrupção MI.
- GitHub Actions run `41563635381` foi iniciado para o commit de produção; consultar conclusão após o commit de testes.
- Nenhum teste visual de ROM nem instalação/execução no Android 16 foi realizado nesta rodada.
- Branch `dev/n64-clean-start`; `main` permanece intacta.


## 46. Ajuste do teste de interrupção COP0 Count — 2026-11-03

- A primeira regressão de Count usava N64Bus e uma sequência de comandos MI; o teste confirmou a linha MI pendente, mas não chegou a confirmar EXL. As execuções falharam no assert de entrada da exceção, portanto não foram aprovadas.
- Para isolar especificamente o caminho de retorno antecipado de `serviceMiInterrupt()`, o teste foi simplificado para iniciar com Cause.IP2 pendente e Status.IE + Status.IM2 habilitados em uma CPU com N64Memory. Ele verifica Count=41 e EXL após um passo de aceitação da interrupção.
- O fixture anterior de 32 bytes foi inicialmente trocado por 8 KB, seguindo os testes de N64Bus; essa mudança, sozinha, não resolveu o assert de entrada da exceção.
- O teste agora não pretende validar a ligação MI do N64Bus; a integração barramento MI -> Cause.IP2 -> serviço CPU permanece uma cobertura separada a desenvolver.
- Commit do teste isolado: `4295e8c06725a8cb0e3e7a0d28ca8014c5501a3a`.
- A execução de CI correspondente está pendente/precisa ser consultada; não declarar sucesso até o término de todos os testes.
- A correção de produção Count no caminho de entrada MI permanece no commit `edacdbd313120f0ae5fb076d52297ea305f5f1c4`; a CI desse commit concluiu success.
- Branch `dev/n64-clean-start`; `main` permanece intacta.

## 47. COP0 Count — fase do divisor em reset e contexto (2026-11-03)

- Auditoria identificou que `countCycleRemainder`, a fase interna do divisor de dois ciclos do Count, não era zerada em `reset()` nem fazia parte de `ContextSnapshot`.
- Isso podia carregar meia fase de temporização de uma execução anterior após reset ou alterar o instante do próximo tick ao restaurar uma CPU suspensa.
- Correção: `reset()` agora zera a fase; `ContextSnapshot` inclui `countCycleRemainder`; `snapshotContext()` captura e `restoreContext()` restaura esse valor. A restauração valida que a fase esteja no intervalo 0..1 antes de alterar o estado arquitetural.
- Testes adicionados: `resetClearsCountDividerPhase` e `cpuContextSnapshotPreservesCountDividerPhase`.
- Commits: `7f2fa41a2e20d5c692a3fef5e03705fd55b7d345` (produção) e `92b6a789b5f605f3ea74c38fd658cea4d5a907cc` (testes).
- GitHub Actions: run `41566402515` (produção) e `41566408129` (testes) concluíram ambos com `success`; build, testes e upload do APK de depuração passaram.
- A temporização do Count continua aproximada: dois ciclos-base por step, sem tabela de latência VR4300 ou stalls de dispositivos. A correção preserva a fase desse modelo, não o torna cycle-accurate.
- Nenhuma execução de ROM no aparelho foi validada nesta etapa. Branch `dev/n64-clean-start`; `main` permanece intacta.

 
## 48. Continuação da auditoria R4300/COP1 — FR, aritmética e comparações (2026-11-03)

### Estado confirmado nesta rodada
- A documentação anterior estava atualizada somente até a seção 47; os commits COP1 mais recentes ainda não tinham sido acrescentados ao registro. Esta seção fecha essa lacuna.
- Branch de trabalho: `dev/n64-clean-start`. A branch `main` não foi alterada.

### FR=0 / FR=1 e transferências COP1
- O acesso aos registradores FPR foi centralizado em helpers que consultam Status.FR (bit 26).
- FR=1: operações double usam um FPR de 64 bits.
- FR=0: operações double usam o par even/odd, com a metade alta no registrador par e a baixa no ímpar; operações single continuam acessando palavras de 32 bits independentes.
- LDC1/SDC1 e aritmética double usam esses helpers. Registradores ímpares em operações double FR=0 geram Reserved Instruction.
- Testes cobrem double FR=0 com par, FR=1, registrador ímpar inválido, LDC1/SDC1 ímpar inválido e independência de single words.
- Correção de expectativa MFC1: leitura de palavra é sign-extended para o GPR de 64 bits; o teste foi ajustado.

### Aritmética COP1
- Subconjunto S/D implementado: ADD, SUB, MUL, DIV, SQRT, ABS, MOV e NEG.
- Instruções unárias exigem campo ft=0; campo malformado gera Reserved Instruction antes de alterar o destino.
- Testes incluem raiz quadrada válida, codificação unária inválida e preservação do FPR destino em RI.

### Comparações COP1 e FCC0
- Implementadas comparações S/D para condições de igualdade, menor que e unordered, usando os bits de condição da função COP1.
- A comparação atualiza FCC0 em FCR31 (bit 23); condições unordered reconhecem NaN.
- BC1T/BC1F já consomem FCC0; teste de integração verifica C.LT.S -> BC1T e execução do delay slot.
- Outro teste confirma que comparação falsa limpa FCC0.
- Limite conhecido: somente FCC0 é suportado. Outros condition-code fields são rejeitados com Reserved Instruction; não anunciar suporte a FCC1..FCC7.

### Commits e CI
- `77527111ba5c0d5309b7350b970af1dfacd88a` — implementação inicial FR-aware (histórico; CI inicial falhou).
- `1ca12e9a05ce6a22e5ad4f5dc79b6c691f2a0d16` — teste aritmético FR=1; CI `41567031424` success.
- `0611c076d17a22fda9fbffe4820b158015146baa` — testes double FR=0 e RI ímpar; CI `41567306737` success.
- `c6687190220cfc75f971110318949d32ee025390` — testes extras de memória; CI `41567509705` failure devido à expectativa MFC1; não considerar aprovado.
- `cfb4d34b07863585da8a2f0691fe615d6d2cef1c` — corrige expectativa sign-extension MFC1; CI `41567547551` success.
- `5f1294d869374c3fc372c898a84bc9000098326f` — valida campos reservados das operações unárias; CI `41567881454` success.
- `48cd7a6dbb7d6bc45ef85d208cc9dd18cdc3eafc` — testes SQRT.S e unária inválida; CI `41567886178` success.
- `60176ca46e81bd553c92e6972959946390493836` — teste destino ímpar em double FR=0; CI `41567941259` success.
- `97bac00f2775ce3a95d9c2823a8020c204544199` — comparações COP1 S/D e FCC0; CI `41568154129` success.
- `7e735e93bdeb393ff85ea56d95a0a1bfb6f31a6c` — testes comparação less-than, unordered/NaN e igualdade double FR=0; CI `41568158658` success.
- `c6cd90e329e44c5f92ab49cbfe11c60d098bc14d` — testes FCC0 -> BC1T, delay slot e limpeza FCC0 quando falso; CI `41568201826` success.
- A CI valida build/testes automatizados. Não comprova execução de ROM no aparelho.

### Pendências explícitas
- Conversões COP1 (CVT.S, CVT.D, CVT.W/CVT.L e variantes ROUND/TRUNC/CEIL/FLOOR) ainda não implementadas.
- FCR31 ainda não modela integralmente modos de arredondamento, flags Cause/Enable/Flag e traps IEEE-754.
- FCC1..FCC7 ainda não implementados.
- Precisão e comportamento de NaN, subnormais, overflow/underflow e exceções ainda precisam de auditoria conforme o VR4300.
- A implementação aritmética usa operações Float/Double da JVM; não é ainda uma FPU cycle-accurate nem garante todos os detalhes de arredondamento/exceção do hardware.
- Não houve instalação/execução visual de ROM no Android 16 nesta rodada.

### Próxima etapa
- Auditar e implementar conversões COP1 com instruções e modos explicitamente testados.
- Depois, tratar FCR31/IEEE exceptions e ampliar os testes de comparação/FCC.
- Manter a regra: corrigir somente defeitos reproduzidos, exigir CI verde e registrar cada etapa antes de declarar concluída.


## 49. Primeira etapa de conversões COP1 — 2026-11-03

### Implementação enviada para validação
- Iniciada a auditoria das conversões COP1 após aprovação das comparações S/D e integração FCC0 -> BC1T.
- Implementadas conversões entre formatos S e D: `CVT.S` e `CVT.D`.
- Implementadas conversões para word: `CVT.W`, `ROUND.W`, `TRUNC.W`, `CEIL.W` e `FLOOR.W`.
- `CVT.W` consulta FCR31.RM (bits 1:0): nearest-even, toward-zero, toward +infinity e toward -infinity.
- Para NaN/overflow de conversão para word, o código grava o valor indefinido MIPS `0x80000000`. A atualização de FCR31 Cause/Flag/Enable e traps de exceção IEEE ainda não foi implementada; portanto, esta é uma primeira cobertura funcional, não a implementação completa da FPU.
- Validação de ft=0 adicionada para as codificações de conversão e operações unárias.

### Testes adicionados
- CVT.S.D e CVT.D.S verificam conversão entre precisão dupla e simples.
- CVT.W.S verifica modo FCR31 de arredondamento para +infinito.
- ROUND.W.S verifica empate para o par mais próximo (2.5 -> 2, ties-to-even).
- TRUNC.W.S verifica truncamento em direção a zero para número negativo.

### Commits e CI
- `e314e169f16d973190664a307deb452b4f4d9d66` — implementação inicial de conversões COP1.
- `4dc3a64c0b9c3fe7ca70cdf71bf0f6df924c757d` — testes de conversão.
- GitHub Actions `41568617952` (implementação) e `41568652771` (testes) estavam em execução no momento do registro. Consultar conclusão antes de considerar aprovado.
- A execução `41568434593` da documentação anterior concluiu success; `41568439782` também concluiu success.
- Ainda não implementados: conversões para long (CVT.L e ROUND/TRUNC/CEIL/FLOOR.L), conversões de inteiro W/L para S/D, exceções IEEE completas, FCR31 flags/cause/enables, traps, precisão completa de NaN/subnormais e FCC1..FCC7.
- Nenhuma execução visual de ROM ou instalação no Android 16 foi realizada nesta etapa. CI não comprova funcionamento em aparelho.
- Branch `dev/n64-clean-start`; `main` permanece intacta.


## 50. Expansão das conversões COP1 para W/L — 2026-11-03

### Implementação
- Ampliado o decoder COP1 para aceitar os formatos fonte W (0x14) e L (0x15) nas instruções de conversão.
- Adicionados `CVT.L`, `ROUND.L`, `TRUNC.L`, `CEIL.L` e `FLOOR.L`, com destino de 64 bits respeitando FR=0 (par par/ímpar) e FR=1.
- Adicionadas conversões de inteiro para ponto flutuante: `CVT.S.W`, `CVT.D.W`, `CVT.S.L` e `CVT.D.L` via os formatos W/L e funções CVT.S/CVT.D.
- Conversões W/L para S/D usam interpretação assinada dos valores inteiros.
- Adicionada validação para rejeitar comparações e conversões para inteiro quando o formato fonte não é S/D.
- Conversões L para Double usam o tipo Double da JVM; limites de int64 são verificados antes de converter, usando o valor indefinido Long.MIN_VALUE em NaN/overflow.
- Esta implementação ainda não atualiza FCR31 Cause/Flag/Enable nem gera traps IEEE-754. A conversão numérica é uma aproximação funcional e precisa de validação mais profunda contra a especificação VR4300.

### Testes adicionados
- `CVT.L.S` com arredondamento FCR31 para -infinito.
- `CVT.S.W` com inteiro negativo.
- `CVT.D.L` com registradores pareados em FR=0.
- Mantidos os testes anteriores para CVT.S/D, CVT.W, ROUND.W e TRUNC.W.

### Histórico de CI e correções
- `3c6528f9a65797dfdc072224c5b281eab80ae0b7` — primeira expansão W/L; CI `41568921523` falhou na compilação por literal hexadecimal de ponto flutuante não suportado pelo Kotlin.
- `3694d0283e00af40251bd42fdecee206a8bd8b6a` — substituição dos limites hexadecimais por limites Double explícitos; CI `41568959183` em execução no momento do registro.
- `348de2bdf6b3b89b711141ea4f1b16921e2b7989` — testes W/L; CI `41568987410` em execução no momento do registro.
- `d3814f559f17b45c1d33a338865fa65ea938a07f` — validação dos formatos fonte; CI `41569054586` em execução no momento do registro.
- A falha anterior está registrada e não é tratada como aprovação. Consultar os resultados das CIs antes de declarar esta etapa concluída.
- Branch de trabalho `dev/n64-clean-start`; `main` não foi alterada.
- Nenhum teste de ROM em aparelho Android foi realizado nesta etapa.


## 51. Correção do decoder de formatos W/L — 2026-11-03

- A CI dos testes `41568987410` falhou em dois casos: conversão W->S e L->D.
- A análise do código mostrou que os formatos W/L já eram tratados dentro da lógica de conversão, mas o dispatch externo COP1 ainda aceitava somente fmt S/D (0x10/0x11). Portanto, as instruções com fmt W/L nunca chegavam à lógica recém-adicionada.
- A CI `41569054586` também falhou por esse mesmo defeito no decoder; não considerar os commits anteriores aprovados.
- Corrigido o dispatch externo para aceitar fmt 0x14 (W) e 0x15 (L): commit `c754775102e6e836f52381b15c71fdbff9a98511`.
- A CI deste commit e a validação final de testes ainda precisam ser consultadas. Este é o primeiro commit que contém o decoder externo corrigido.
- O erro de literal Double no commit inicial já foi corrigido em `3694d0283e00af40251bd42fdecee206a8bd8b6a`, cuja CI de compilação concluiu success; isso não substitui a CI de testes que falhou posteriormente.
- Acompanhar especialmente os testes `cvtLongAndCvtSingleFromWordUseSignedIntegerFormats`, `cvtDoubleFromLongUsesFr0RegisterPair` e `cvtLongUsesFcr31RoundingMode`.


## 52. FCR31: máscara de campos graváveis via CTC1
- Commit de produção: `9951f9fb73eb16a5d57d8e0b243c522f45722c9a`.
- `CTC1` direcionado ao FCR31 agora preserva somente RM (bits 1:0), Flags (6:2), Enables (11:7), Cause (17:12), FCC0 (23) e FS (24), usando máscara `0x0183FFFF`.
- Bits reservados escritos pelo software são descartados; FCR0 continua somente leitura.
- Teste automatizado adicionado no commit `901885f00537984345b0a73e0d5a27ad86d78de3`, incluindo verificação dos campos individualmente.
- CI da alteração de produção/teste ainda precisa ser confirmado. Isso prepara o controle do FPU, mas ainda não implementa Cause/Flag automáticos nem traps IEEE.


## 53. FCR31: Cause/Flag básicos e trap por divide-by-zero
- CI da máscara CTC1/FCR31 aprovada: produção `9951f9fb73eb16a5d57d8e0b243c522f45722c9a` (run `41569578981`); teste `901885f00537984345b0a73e0d5a27ad86d78de3` (run `41569603185`); documentação (run `41569632560`). Todos concluídos com sucesso.
- Commit `55fb2806f8b9c101dc9b94309099cc02dcad1b91`: inicia o rastreamento básico de exceções FPU; Cause é substituído por operação, Flags são acumuladas, e exceção habilitada dispara FPE (ExcCode 15) antes da escrita do destino.
- Commit `6463f5b9bc29dde71527c3abc3ee7d42636b3b72`: corrige alinhamento entre Cause e Enable.
- Commits de teste `343d91a01b420843b5d569fc4cda02c1509faf68` e `47a437ea9399716bc4c60e08e817a9d3c997fbe8`: cobrem divide-by-zero habilitado/desabilitado, flags e preservação do destino no trap.
- Escopo atual é limitado: detecção básica de divide-by-zero, invalid em alguns casos aritméticos/SQRT negativo e overflow finito; não é implementação IEEE completa. Inexact, underflow, NaN signaling, FCR31 Unimplemented e todos os detalhes de precisão ainda precisam de trabalho. CI dos commits 55fb280/6463f5b/47a437e ainda pendente.


## Correção da asserção FCR31 — 2026-11-03

- A CI `41569903012` identificou erro de compilação em `R4300Cop1ArithmeticTest.kt:214`: a expressão `1L shl 12..17` produzia `IntRange`, mas o operador `and` exigia `Int`/`Long`.
- Corrigido no commit `779c1468d950397376748ce5ecc724ca8ce55ee2`: a máscara de Cause passou a ser `(0x3FL shl 12)`, cobrindo explicitamente os bits 17:12.
- A falha era no código do teste, não no código de produção. A implementação de FPU compilou e o APK Debug foi montado na execução que falhou na compilação dos testes unitários.
- A nova CI deste commit ainda precisa terminar. Não considerar a etapa aprovada até confirmar `success`.
- Permanecem as limitações de exceções FPU descritas acima: classificação parcial, sem Inexact/Underflow/NaN signaling completo, e sem teste de ROM em dispositivo.


## Ampliação dos testes de exceções FPU — 2026-11-03

- Após a correção da máscara de Cause, as CIs dos commits 779c1468d950397376748ce5ecc724ca8ce55ee2, 32971215b979b3e3454e13bfa0735029096311e2 e cad15c5155f98853f1245e13f85d50d626bfdd39 concluíram com sucesso (runs 41570279292, 41570310432 e 41570319123).
- Novos testes adicionados no commit 994009656444241a8ded4cd6a88ab8cc5718ae3f:
  - 0.0 / 0.0 gera Cause Invalid (bit 16), ativa Flag Invalid e preserva uma Flag anterior (sticky).
  - Float.MAX_VALUE * 2.0f em precisão simples gera Cause Overflow (bit 14), Flag Overflow e infinito como resultado quando Enable está desligado.
  - Com Enable Invalid ligado, 0.0 / 0.0 gera FPE (ExcCode 15) e não altera o registrador destino.
- A CI dos novos testes ainda precisa concluir. Esses testes ampliam a cobertura do classificador existente; não adicionam novas categorias de exceção à implementação.
- Limites permanecem: classificação básica e parcial, sem implementação integral de Underflow/Inexact/NaN signaling/Unimplemented; não há validação de ROM no aparelho Android 16.


## 54. Expansão de testes Invalid do COP1 — 2026-11-03

- Confirmadas como aprovadas as três CIs anteriores: testes COP1 41570561326, atualização da continuidade 41570609730 e atualização da auditoria 41570618132 (todas success).
- Commit 3f3a6093e11e60de90daedcc2c20d791264a43d6 adiciona dois testes de regressão:
  - SQRT.S de valor negativo com Enable Invalid ativo: verifica Cause Invalid, Flag Invalid, ExcCode 15 e que o registrador destino não é alterado.
  - DIV.S infinito/infinito com Enable desligado: verifica Cause/Flag Invalid, ausência de trap e resultado NaN.
- Esses testes exercitam dois caminhos já previstos no classificador; não adicionam novas categorias de exceção nem ampliam a implementação de produção.
- CI do commit de testes ainda precisa ser consultada. Se falhar, buscar o log da tarefa e corrigir a causa antes de atualizar o status.
- Permanecem pendentes Underflow, Inexact, NaN signaling, Unimplemented e validação completa IEEE-754/VR4300. Nenhuma ROM foi executada em aparelho Android 16 nesta etapa.
- Branch exclusiva: dev/n64-clean-start; main não foi alterada.


## 55. Mudança de prioridade: primeiro jogo executável — 2026-11-03

- Decisão do usuário: priorizar criar rapidamente um caminho funcional para testar pelo menos um jogo; depois do primeiro teste, retomar desenvolvimento normal e ampliar compatibilidade.
- Inspeção confirmou infraestrutura já existente: N64RomLoader, N64HleBoot, N64RomVideoSession, N64ViFramebufferReader, N64VideoPresenterView, parser/processador DPC/RDP e rasterizadores iniciais.
- Bloqueio arquitetural mais próximo: N64RomVideoSession não descobre/agenda automaticamente OSTasks libultra; RSP é acionado por polling cooperativo quando SP HALT está liberado. O caminho gráfico e microcódigos não estão completos.
- Criado docs/PLANO_PRIMEIRO_JOGO.md com sequência de MVP, critério de sucesso e funcionalidades adiadas. O primeiro quadro só conta se vier de uma ROM real e aparecer no Galaxy A15; CI ou padrão sintético não contam.
- Próxima informação necessária: nome/região da ROM que o usuário pretende testar, para escolher o primeiro alvo e evitar trabalho genérico sem referência.
- As CIs dos testes COP1 e das duas atualizações documentais anteriores (41570938873, 41570983496, 41570990915) concluíram success. A prioridade de trabalho muda; não significa que ROM já executa.
- Branch exclusiva: dev/n64-clean-start. main não foi alterada.


## 56. Alvo definido: GoldenEye 007 — 2026-11-03

- O usuário escolheu **GoldenEye 007 (Nintendo 64)** como primeiro jogo do MVP.
- `N64Bus.detectCicSeed()` reconhece a soma IPL3 `0xD057C85244` como seed `0x3F` (CIC-NUS-6102/7101), comentado como usado por GoldenEye 007 USA. Isso não comprova compatibilidade de boot; apenas identifica uma variante CIC.
- `N64RomVideoSession` continua sem descoberta/agendamento automático de OSTasks; `N64Bus` tem traces de SP MMIO/DMA e processa DPC_END de forma síncrona, com limitações.
- Referências técnicas públicas da descompilação descrevem microcódigo gráfico Rare baseado em F3D, com extensão `G_TRI4` (opcode `0xB1`). É uma hipótese técnica forte para orientar inspeção, não suporte já implementado. Confirmar com microcódigo e display lists da ROM concreta.
- Próxima implementação: detecção/diagnóstico de OSTask e microcódigo carregado; depois adicionar somente comandos F3D/G_TRI4 e RDP exigidos pelos traces. Não inventar OSTasks nem limpar HALT sem evidência.
- Região/edição da ROM ainda não informada. Pedir USA, Europe/PAL ou Japan; não presumir região. Não solicitar nem armazenar ROM protegida.
- Plano específico atualizado em `docs/PLANO_PRIMEIRO_JOGO.md`.
- Nenhuma ROM foi executada e nenhum jogo foi confirmado como funcional. CI não equivale a teste real no aparelho.
- Branch exclusiva: `dev/n64-clean-start`; `main` não foi alterada.


## 57. GoldenEye 007 USA e diagnóstico inicial de OSTask — 2026-11-03

- Usuário confirmou que a ROM-alvo é **GoldenEye 007 USA**. A edição é compatível com a hipótese CIC-NUS-6102/7101 (seed `0x3F`) já reconhecida pelo código; validar checksum/cabeçalho da ROM concreta antes de afirmar identificação.
- Implementado `N64Bus.diagnosticSpTaskDescriptor()`: lê os 16 campos do descritor OSTask convencional localizado em SP DMEM + `0x0FC0`, sem efeitos colaterais.
- Teste `diagnosticReadsConventionalLibultraTaskDescriptorFromDmem` cobre todos os 16 campos e a leitura big-endian.
- Commits: produção `8135a0e9c8654627c5ca76290aa183f9184236e9`; teste `981f0b64ebc44cbeafb55c17f03972fac885d499`.
- Limite explícito: isto ainda NÃO agenda nem executa OSTasks; é observabilidade para correlacionar o descritor com SP DMA, SP_PC e SP_STATUS. Não liberar HALT automaticamente nem executar tarefas artificiais.
- CI do commit de produção/teste: run `41571771059`, ainda em andamento no momento deste registro. Confirmar antes de declarar sucesso.
- Nenhuma ROM executada no aparelho; nenhum quadro real do GoldenEye confirmado.
- Branch exclusiva: `dev/n64-clean-start`; `main` não foi alterada.


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


## 59. Correção ABI do descritor OSTask — 2026-11-03

- Revisão do layout de 64 bytes encontrou os dois pares finais invertidos em `N64Bus.diagnosticSpTaskDescriptor()`.
- Corrigido: offsets 0x30/0x34 agora alimentam `dataPointer/dataSize`; offsets 0x38/0x3C alimentam `yieldData/yieldDataSize`, conforme a ordem ABI padrão.
- Teste atualizado com valores distintos nos dois pares, para detectar regressão.
- Commits: produção `d0700f132f3a468051fe22c3445c2bb743f2fe2c`; teste `bb9a06c48444e98a6e8f6e5d840b1bae579009ad`.
- CI do código/teste ainda precisa ser confirmada. A CI dos documentos da etapa anterior passou (runs 41572153473, 41572158841, 41572164048 success).
- Não muda o estado de integração OSTask: sessão contínua ainda não despacha automaticamente tarefas; sem ROM executada no aparelho.


## 60. Captura passiva de DMA do descritor OSTask — 2026-11-03

- Adicionado ao `N64Bus` o histórico limitado `diagnosticSpTaskDescriptorDmaHistory`.
- O registro só é criado quando ocorre DMA RDRAM→SP exatamente em DMEM + 0xFC0, com 64 bytes transferidos. O evento inclui PC da CPU, endereço RDRAM e campos principais do OSTask (type/flags, boot ucode, ucode, data e yield).
- A captura ocorre após a transferência real terminar. Não libera HALT, não escreve SP_PC, não chama `N64SpTaskRunner` e não agenda execução.
- Teste `recordsOnlyCompleteConventionalOstaskDmaAsPassiveEvidence`: garante que DMA em outro offset não é classificado como OSTask, que a transferência canônica é registrada e que SP HALT permanece inalterado.
- Commits: produção `7e7eeee4ec9068f180835e174bf2dcea24f0272c`; teste `6313a8417d59188fe833aa02120e9e6c444962ad`.
- CI dos commits ainda estava em andamento na última consulta (runs 41601276572 e 41601311466). Aguardar resultado antes de validar.
- Próximo passo após CI: acrescentar ao diagnóstico a correlação entre descritor observado, DMA de boot microcode para IMEM, SP_PC e comando CLR_HALT, mantendo observação passiva. Só depois de validar a sequência real da ROM projetar despacho.


## 61. Ajuste do formato do log OSTask — 2026-11-03

- A primeira CI do teste de captura passiva encontrou falha apenas na expectativa de formatação: o relatório imprimia ponteiros sem zeros à esquerda, enquanto o teste/documentação exigia endereços de 8 dígitos.
- Padronizados BOOT, UCODE, DATA e YIELD com 8 dígitos hexadecimais. Não houve mudança na lógica de detecção DMA.
- Commit de ajuste: `dd614e6d0051bb1e256c962cbfd515d19f1fd5cd`.
- A CI que falhou foi run 41601311466; produção da captura passiva (run 41601276572) passou. Aguardar CI do commit de formatação e do export da MainActivity antes de declarar o conjunto validado.
- O relatório da MainActivity agora inclui a seção de descritores OSTask detectados, com aviso explícito de que é observação passiva; commit `c29534a586c5d91c7166924c9b5c784fb2b097ed`.


## 62. Limpeza conservadora de duplicações — 2026-11-04

- Removida de `MainActivity.kt` a função sem chamadas `runIpl3HandoffDiagnosticLegacy()` (364 linhas de corpo). O diagnóstico HLE atual permanece intacto. Commit `d0997d797651908c659955348ee8bde06d2c5a19`.
- Os dois blocos de exportação dos eventos do barramento (caminho normal e catch) foram centralizados em `appendBusDiagnostics(lines, bus, heading)`, preservando os cabeçalhos distintos e os mesmos campos exportados. Commit `d4f88c4d9ffdb344a8dd72952c16e8755a287cac`.
- Auditoria e justificativas de componentes mantidos em `docs/LIMPEZA_DUPLICACOES_2026-11-04.md`.
- Os rastreadores `N64HleInstructionTrace` e `N64InstructionTrace` foram mantidos: têm finalidades parcialmente sobrepostas, mas recursos e testes distintos.
- `N64RdpFillProcessor.execute()` e `executePackets()` foram mantidos: o segundo valida pacotes e estende a execução para comandos multiword/triângulos, delegando single-word ao primeiro.
- Módulos de sessão, bootstrap/runner/lifecycle SP e core/unidade vetorial RSP foram mantidos por terem responsabilidades diferentes.
- CI Android build dos commits de código `d0997d7` e `d4f88c4` estava em execução na última consulta (runs 41799039823 e 41799094882). Não declarar validação final até verificar os resultados.
- Branch exclusiva `dev/n64-clean-start`; nenhuma alteração foi feita na `main`.


## 63. Resultado da CI e continuação da auditoria de duplicações — 2026-11-04

- Os quatro workflows Android build da limpeza foram consultados: runs `41799039823`, `41799094882`, `41799163527` e `41799184370` concluíram com sucesso. Em todos, Build and test e Upload debug APK passaram.
- A documentação detalhada foi atualizada em `docs/LIMPEZA_DUPLICACOES_2026-11-04.md`, commit `c4a07d7b4fef29eee87532ffca70162d8a7b6ea1`.
- Continuação da revisão estrutural: `N64HleCpuScheduler` e `N64HleOsKernel` têm métodos de despacho/threads com nomes próximos, mas o scheduler salva/restaura contextos R4300 e o kernel mantém estados, filas e eventos; mantidos.
- Pipeline RDP mantido em camadas: parser de pacotes, decoder de setup de triângulo, edge walker e rasterizador têm entradas/saídas diferentes. O caminho de vídeo também separa leitura VI e apresentação Android.
- A auditoria continua sendo estática/estrutural, não AST/clone local exaustivo dos 122 arquivos. Não declarar que toda duplicação do repositório foi eliminada.
- Nenhuma nova mudança de código de emulação foi feita nesta etapa; apenas documentação e verificação CI.
- Próximo passo seguro: continuar a inspeção de utilitários de ROM/memória/CPU e dos testes correspondentes, buscando duplicação literal comprovada antes de qualquer refactor. Não alterar `main`; branch de trabalho `dev/n64-clean-start`.
- CI não significa APK instalado no aparelho nem GoldenEye funcional. Teste real no Android 16 ainda precisa ser feito pelo usuário.


## 64. CPU R4300 — auditoria de repetição de redirecionamento de exceção — 2026-11-04

- Revisados `R4300Cpu.kt` (1.909 linhas), a estrutura de seus métodos e os testes `R4300CpuTest`, `R4300MiInterruptTest`, `R4300TlbExceptionAccessTest`, `R4300TlbStoreDelaySlotTest` e `R4300Cop0CountTimerTest`.
- Encontrado um bloco repetido em cinco caminhos: quatro handlers dentro de `step()` e `enterAddressException()` atribuíam o vetor geral e limpavam `delayedTarget` e `pendingDelaySlotBranchAddress`.
- Criado `redirectToGeneralExceptionVector()` e substituídas as cinco cópias. Commit `ac2a744070e31f3297bfaefd69c96e7e04bddf8b`.
- Não foram fundidos os caminhos TLB: eles escolhem refill/general vector de acordo com MISS/INVALID/MODIFIED, EXL e BEV. Também não foram alterados os casos de overflow tratados dentro do decoder, que seguem o fluxo de delay slot da instrução.
- A refatoração não adiciona suporte MIPS nem muda os códigos de exceção. A CI para o commit de código ainda deve ser consultada.
- A auditoria de CPU é estrutural, não prova de cobertura integral nem validação de hardware. Próximo passo: aguardar CI e continuar a revisão de grupos de instruções/testes procurando duplicação comprovada; evitar dividir o decoder de 900+ linhas sem plano de testes por opcode.
- Branch `dev/n64-clean-start`; `main` permanece intocada.


## 65. Auditoria dirigida do decoder R4300 — 2026-11-04

- CI dos commits ac2a744 (refatoração CPU) e ac12177 (documentação) consultada: runs 41800334365 e 41800415861 concluíram com sucesso, incluindo Build and test e Upload debug APK.
- Revisados estaticamente R4300Cpu.kt (1.906 linhas na versão consultada) e R4300CpuTest.kt (243 declarações @Test).
- Grupos verificados: aritmética 32/64 bits e overflow; multiplicação/divisão HI/LO; desvios, delay slots e branch-likely; exceções em delay slot; acessos desalinhados e merge; parte de COP0/TLB/LL-SC.
- Nenhuma nova duplicação de lógica nem falha funcional foi confirmada nesta passada. O decoder de produção não foi alterado.
- Correção após inspeção integral do bloco de testes: LDL/LDR e LWL/LWR percorrem todos os offsets; SWL/SWR também percorrem todos os offsets de palavra; SDL/SDR percorrem todos os offsets de doubleword. Não foi identificada lacuna de cobertura de offsets para merge loads/stores.
- Relatório detalhado: docs/AUDITORIA_R4300_INSTRUCOES_2026-11-04.md, commit a13153a3c4bd392c6169fd60a0690a910a076c2e.
- Limites: CI verde confirma build/testes automatizados, não compatibilidade integral R4300, instalação do APK no aparelho ou GoldenEye funcional. main permanece intacta; trabalho continua em dev/n64-clean-start.


## 66. Auditoria COP0, TLB e COP1 — 2026-11-04

- Revisão estática de R4300Cpu.kt, R4300Tlb.kt e testes específicos COP0/TLB/COP1.
- COP0/TLB: identificada cobertura abrangente para TLBR/TLBWI/TLBWR/TLBP, Index/Random/Wired, PageMask, EntryHi/Lo, globalidade, ASID, faults, Count/Compare, ERET e snapshot/restore. Nenhum defeito funcional comprovado nesta inspeção; nenhuma alteração de produção.
- COP1: cobertura de transfers, operações S/D básicas, conversões, FCC0, FR=0/FR=1 e exceções selecionadas. Limites reais mantidos: FPU parcial; exceções IEEE não completas (incluindo underflow/inexact); não demonstrado que RM do FCR31 controle o arredondamento de ADD/SUB/MUL/DIV/SQRT; somente FCC0 suportado.
- Relatório detalhado: docs/AUDITORIA_COP0_TLB_COP1_2026-11-04.md, commit dd9ff6f944dd77426f1ad163c4339d19e4e75c95.
- Próxima etapa: criar testes de conformidade COP1 para RM, underflow/inexact, NaN/sinal de zero e comparações signaling/non-signaling antes de implementar mudanças. Continuar auditoria de Status e instruções privilegiadas com testes isolados.
- Nenhuma ROM adicionada. Nenhum código de produção alterado. main permanece intacta; trabalho em dev/n64-clean-start. CI não significa execução de jogo no Android 16.


## 67. Auditoria de duplicação — DPC/RDP (2026-11-04)

- Continuidade da auditoria geral do repositório na branch `dev/n64-clean-start`; `main` permanece intocada.
- Revisados `N64RdpCommandLength.kt`, `N64RdpCommandStreamParser.kt`, `N64DpcCommandProcessor.kt`, trechos de `N64Bus.kt`, `N64VideoFrame.kt` e testes DPC/parser/framebuffer.
- Duplicação funcional confirmada: `N64DpcCommandProcessor.process()` repetia as validações de faixa, alinhamento, limites de RDRAM e tamanho máximo que já são centralizadas em `N64RdpCommandStreamParser.parse()`.
- Refatoração aplicada: removidas as validações repetidas de `process()`; o parser passa a ser a única fonte de validação e framing do fluxo. O construtor ainda valida a configuração do processador.
- Commit: `0059a1b216cc01b3ca06e16141b2c4281240045b`.
- Testes existentes cobrem intervalo vazio, alinhamento, faixa fora da RDRAM, limite máximo e pacotes multiword truncados.
- Duplicação de teste confirmada em `N64DpcCommandProcessorTest.kt`: `writeCommands()` repetia a escrita de palavras de 64 bits já feita por `writeWords()`. Agora `writeCommands()` delega a `writeWords()`.
- Commit: `532d8b212b91468ea2b9555d3992663913fbb9da`.
- CI do commit `0059a1b...`: run `41801775599` estava em execução na última consulta (etapa Build and test); não declarar sucesso até confirmar.
- A auditoria ainda não cobriu integralmente todos os arquivos do repositório. Não confundir semelhança de responsabilidades com duplicação; não fundir parser, decoder, rasterizador e leitor de framebuffer.
- Próxima etapa: confirmar CI dos commits acima e continuar comparação sistemática dos módulos restantes, especialmente SP/RSP e testes correspondentes. Manter a distinção entre duplicação literal, duplicação funcional e abstrações intencionalmente separadas.


## 68. Auditoria de arquivos repetidos e SP/RSP (2026-11-04)

### Varredura de arquivos
- Árvore Git recursiva da branch `dev/n64-clean-start`, commit-base `59ba65453116cf08f33b80367fbe9f07d187e7a4`: 125 arquivos; resposta da árvore sem truncamento.
- Comparação dos Git blob SHAs: nenhum grupo de arquivos com conteúdo byte a byte idêntico.
- Comparação de nomes-base sem diferenciar maiúsculas/minúsculas: única colisão é `build.gradle.kts` na raiz e `app/build.gradle.kts`. São arquivos de configuração Gradle em escopos diferentes, não cópias redundantes; manter ambos.
- Nenhum arquivo foi apagado. Não há evidência de arquivo duplicado exato nesta revisão.
- Esta varredura não detecta arquivos semanticamente equivalentes, cópias com pequenas diferenças ou código repetido dentro de arquivos.

### SP/RSP — revisão estática
- Revisados `N64SpTaskBootstrap`, `N64SpTaskDescriptor`, `N64SpTaskDpCoordinator`, `N64SpTaskLifecycle`, `N64SpTaskMicrocodeTracker`, `N64SpTaskRunner`, `N64RspCore` e `N64RspVectorUnit`.
- Revisados testes SP/RSP correspondentes, incluindo bootstrap, descriptor, coordinator, lifecycle, microcode tracker, runner, core, vector unit e integração R4300 com IMEM.
- Não foi comprovada duplicação de lógica de produção entre essas classes: descriptor decodifica ABI; bootstrap prepara DMA/PC; tracker exige evidência de carregamento e execução; lifecycle controla estados; coordinator sincroniza DP; runner conecta execução; RSP core executa instruções escalares; vector unit mantém estado/operações vetoriais.
- `N64RspCoreTest` e `N64RspVectorUnitTest` exercitam camadas diferentes (decodificação/instruções versus semântica da unidade vetorial); não são arquivos duplicados.
- Há preparação semelhante de cabeçalho OSTask em testes de bootstrap e runner, mas os cenários e os campos utilizados diferem. Não foi extraído fixture compartilhado para evitar abstração de teste sem benefício comprovado.
- Não foram feitas alterações de produção nesta etapa.

### CI confirmada
- Run `41801775599`, commit `0059a1b...`: sucesso, incluindo Build and test e upload do APK.
- Run `41801822912`, commit `532d8b2...`: sucesso, incluindo Build and test e upload do APK.

### Próxima etapa
- Continuar auditoria de duplicações em outros grupos ainda não comparados em profundidade (bus/MMIO, VI/framebuffer/presenter, ROM/diagnóstico e UI).
- Fazer comparação semântica e de chamadas; não confundir nomes parecidos, responsabilidades complementares ou configuração em escopos diferentes com arquivos duplicados.
- `main` permanece intocada.



## 69. Auditoria da interface Android e fluxo de ROM — 2026-11-04

### CI e limpeza pequena
- CI do commit `3ad4b4f4837ca8ebfe87882ddf48b284facd7067` (remoção de guardas redundantes SI/SP DMA) concluiu com sucesso: run `41802414913`.
- Revisado `MainActivity.kt` na branch `dev/n64-clean-start`.
- Removidos cinco imports sem uso (`ClipData`, `ClipboardManager`, `Bitmap`, `ImageView`, `ByteArrayOutputStream`) e o helper sem chamadas `sessionSafeInstructionCount()`.
- Commit da limpeza: `a9225ad384027a2689bb4aa863acc977940898f0`. CI run `41802747240` estava em execução na última consulta; aguardar resultado antes de declarar validado.

### Achados de conectividade da UI
- A tela criada em `onCreate()` contém o título, status, botão principal de selecionar/iniciar ROM e botão de exportação de diagnóstico completo.
- `runVideoTest()`, `runTrianglePipelineDemo()` e `runCpuDiagnostic()` existem, mas não possuem chamadas no código consultado. São caminhos de teste atualmente inacessíveis pela interface.
- `romTraceButton` e `hleBootButton` são campos consultados/habilitados/desabilitados em vários fluxos, mas não são instanciados nem adicionados à tela atual. Os métodos de diagnóstico associados não estão acessíveis pelo menu.
- Não apagar esses caminhos nesta auditoria: são ferramentas de diagnóstico úteis e a decisão correta é planejar sua reconexão ou remoção deliberada depois de confirmar a finalidade atual.
- `sessionSafeFrameCount()` continua em uso no progresso da sessão; somente `sessionSafeInstructionCount()` foi removido.
- `appendBusDiagnostics()` tem chamada nos dois caminhos de exportação/diagnóstico e foi mantido.

### Fluxo de carregamento e execução — observações
- `loadRomFromUri()` lê o documento em thread de fundo, valida via `N64RomLoader`, e usa `romLoadGeneration` para impedir que uma carga antiga sobrescreva uma seleção mais recente. A URI persistida só é salva após ROM validada.
- O seletor usa Storage Access Framework (`ACTION_OPEN_DOCUMENT`) e permissões persistíveis; o Manifest não solicita permissões gerais de armazenamento, coerente com esse fluxo.
- `startRomVideoExecution()` cria `N64VideoPresenterView`, prepara HLE, executa `N64RomVideoSession` e encaminha quadros para a View. A própria tela descreve o caminho como experimental e reconhece que o despacho automático de OSTasks ainda não está implementado.
- `onDestroy()` solicita parada ao worker HLE e à sessão de vídeo. Não foi executado teste de ciclo de vida em aparelho nesta revisão.
- Manifest declara apenas `MainActivity` como launcher; Gradle usa compile/target SDK 36, min SDK 26 e Java/Kotlin 17. Nenhuma duplicação de configuração foi comprovada.
- Não foi feita alteração no fluxo de execução, carregamento ou Manifest nesta etapa.

### Próximo passo
- Consultar o resultado do run `41802747240`.
- Revisar testes e pontos de entrada de UI para decidir como tornar acessíveis os diagnósticos sem duplicar telas ou criar botões desconectados.
- Depois continuar auditoria de recursos Android, ciclo de vida e concorrência; manter a distinção entre função sem chamada, função intencionalmente interna e duplicação funcional comprovada.
- A auditoria global ainda não terminou. `main` permanece intocada; branch de trabalho `dev/n64-clean-start`. CI verde não comprova instalação no Android 16 nem GoldenEye funcional.



## 70. Reconexão dos diagnósticos na interface e exclusão mútua — 2026-11-04

- A partir da auditoria da seção 69, reconectados os campos de botão que já existiam mas não eram instanciados na tela principal:
  - `DIAGNÓSTICO HLE` chama `runHleBootDiagnostic()`.
  - `DIAGNÓSTICO IPL3 / CPU` chama `runRomInstructionDiagnostic()`.
- Ambos começam desabilitados e são habilitados quando uma ROM é carregada/validada; são desabilitados novamente quando a ROM não está disponível.
- Adicionadas verificações de conflito nos listeners para não iniciar diagnóstico enquanto a sessão de vídeo está ativa ou o worker HLE está registrado.
- Os dois diagnósticos desabilitam os botões concorrentes durante a execução. Também desabilitam o botão principal para evitar iniciar uma sessão de jogo simultaneamente. Os caminhos de sucesso, falha e cancelamento reabilitam os controles.
- Corrigidos os caminhos de retorno de falha ao preparar IPL3 e de exceção no worker de diagnóstico para restaurar os controles.
- Commit UI: `b257d06441aa1e7001d32e61a8626dac533b7459`.
- Commit exclusão mútua/restauração de controles: `43b3f4cf62e25616499e81e9fb45b2a022c60495`.
- CI run `41803054416` (commit b257d06) e run `41803147496` (commit 43b3f4c) estavam em execução na última consulta; validar ambos antes de declarar a interface compilada/testada.
- CI anterior confirmado: run `41802747240` (limpeza imports/helper, commit a9225ad) e run `41802823530` (esta documentação/seção 69, commit 2d416aa) concluíram com sucesso.
- Os testes sintéticos de vídeo/RDP e CPU ainda não foram reconectados; ficam para etapa separada, após validação destes dois botões e da exclusão mútua.
- Não foi alterado o motor CPU/RSP/RDP, o boot HLE, a seleção de ROM, nem o Manifest. Nenhum jogo foi executado no aparelho nesta etapa.
- Próximo passo: confirmar os dois workflows; depois decidir como expor os testes de vídeo e CPU sem sobrecarregar a tela principal (preferir uma tela/área de ferramentas de diagnóstico). Fazer testes de UI e lifecycle; não afirmar GoldenEye funcional.
- Branch `dev/n64-clean-start`; `main` permanece intocada.


## 71. Ciclo de vida dos workers e callbacks Android — 2026-11-04

- Revisado o encerramento da Activity, os callbacks de N64RomVideoSession, N64ExecutionWorker, o diagnóstico IPL3/CPU e N64VideoPresenterView.
- Achado confirmado: o worker de vídeo podia continuar publicando atualizações de progresso/frames e tentar atualizar views após a Activity entrar em encerramento. Os callbacks do worker agora verificam isFinishing e isDestroyed antes de postar e novamente dentro do callback da UI.
- O callback de frames também deixa de submeter novos quadros após a Activity começar a encerrar.
- Achado confirmado: o diagnóstico IPL3/CPU tem limite de até 12 milhões de instruções e antes não verificava o encerramento da Activity dentro do laço. Adicionado romTraceStopRequested volátil, ativado em onDestroy(), e verificado a cada iteração. Assim o worker sai cooperativamente no próximo limite de instrução.
- Callbacks de progresso, falha, preparação de IPL3, CIC não reconhecido e relatório final do diagnóstico IPL3 agora verificam se a Activity ainda pode receber atualizações. Caminhos de saída antecipada restauram os botões quando a Activity ainda está ativa.
- O worker de vídeo e o worker HLE já tinham pedido de parada cooperativa; onDestroy() continua solicitando essas paradas. Não foi adicionado join() na thread principal para não bloquear o encerramento da UI.
- Arquivos revisados: MainActivity.kt, N64ExecutionWorker.kt, N64RomVideoSession.kt, N64ExecutionSession.kt, N64VideoPresenterView.kt.
- Commits:
  - 0eab14ca0fbd83394f5649c207d79c8d86b51e17 — proteção de callbacks do worker de vídeo.
  - 10633612add67e9bb6ec911d381f55feae400947 — cancelamento cooperativo do diagnóstico IPL3/CPU ao encerrar Activity.
  - 4f344b666f7e19b8665071efe7a52ff48888eeae — proteção adicional dos callbacks de IPL3/CPU.
- CI dos commits 0eab14c, 1063361 e 4f344b6 estava em execução na última consulta (runs 41803416840, 41803480888 e 41803502234). Confirmar os resultados antes de considerar esta etapa compilada.
- Limitação restante: o diagnóstico IPL3/CPU não oferece cancelamento manual por botão; o cancelamento foi ligado ao encerramento da Activity. O worker de carregamento de ROM usa romLoadGeneration para descartar resultados antigos, mas pode continuar lendo o documento em segundo plano até terminar. A sessão de vídeo solicita parada cooperativa; não foi feito teste de instrumentação/lifecycle em aparelho real.
- Nenhum código de CPU/RSP/RDP foi alterado. Nenhuma ROM foi executada no aparelho. main permanece intocada.
- Próxima etapa: confirmar CI; inspecionar fluxo de seleção/rotação/retorno e o exportador de diagnóstico; depois avaliar teste automatizado de UI/lifecycle e organizar os testes sintéticos numa área de ferramentas sem sobrecarregar a tela inicial.


## 72. Proteção do exportador de diagnóstico após encerramento da Activity — 2026-11-04

- Na revisão do fluxo de exportação, foi confirmado que o trabalho de gravação já ocorre em thread de fundo, mas os callbacks de sucesso, falha e reabilitação do botão ainda podiam tentar atualizar a UI depois de a Activity ser destruída.
- Corrigido em `MainActivity.createCompleteDiagnosticButton()`: os três caminhos de callback agora verificam `isFinishing` e `isDestroyed` antes de postar no thread principal e novamente dentro do callback.
- O exportador passa a usar `applicationContext` para obter `packageName`, `PackageManager` e `ContentResolver`, reduzindo dependência de serviços vinculados à Activity durante a gravação.
- A exportação não é cancelada quando a Activity termina: se já começou, continua tentando salvar o arquivo em Downloads/N64Android; somente notificações e alterações de botão são suprimidas após o encerramento. Isso evita descartar um diagnóstico solicitado pelo usuário.
- ROM loader verificado nesta etapa: `N64RomLoader.readAllBytes()` já limita o fluxo a 64 MiB antes de bufferizar, trata leituras temporariamente zeradas e possui testes de limite exato, excesso e leitura zero temporária. Nenhuma alteração necessária nesse módulo.
- Commit da proteção do exportador: `4e632e28303842d08e1a34420411eea538ae62fb`.
- CI deste commit deve ser consultado antes de considerar a alteração validada.
- Não houve teste em aparelho nem mudança no motor de emulação. `main` permanece intocada; branch de trabalho `dev/n64-clean-start`.
- Próxima etapa: validar CI; continuar a revisão de concorrência entre exportação, troca de ROM e encerramento, e avaliar testes automatizados da Activity sem introduzir dependências instrumentadas sem necessidade.


## 73. Exclusão mútua entre exportação, seleção de ROM e diagnósticos — 2026-11-04

- A revisão de concorrência encontrou um risco real de consistência: durante a exportação, o usuário poderia voltar ao menu e selecionar outra ROM após o worker de vídeo terminar, enquanto o exportador ainda montava o relatório. Isso poderia misturar a referência da ROM/barramento no arquivo com o estado de CPU/sessão da execução anterior.
- Também havia caminhos para solicitar exportação enquanto o diagnóstico HLE ou o diagnóstico IPL3/CPU ainda estava modificando o barramento.
- Adicionado `diagnosticExportInProgress` como trava de operação. Ao iniciar exportação, os controles principais são desabilitados; uma segunda exportação é ignorada; a seleção/troca de ROM e o início de execução/diagnóstico são recusados enquanto a exportação está ativa.
- Exportação é recusada se o worker HLE ou o diagnóstico IPL3/CPU ainda estiver ativo, em vez de ler coleções mutáveis durante a execução.
- Adicionado `romTraceInProgress`, ativado antes de iniciar o worker IPL3/CPU e sempre limpo em `finally`. A trava impede iniciar exportação, trocar ROM ou começar outra execução/diagnóstico enquanto esse worker ainda trabalha.
- Os controles são restaurados no callback final do exportador apenas se a Activity ainda estiver ativa; a habilitação é calculada a partir do estado real dos workers e da ROM carregada.
- Commits:
  - `9cfafe51a9ce1e11b7f801f4e80c5ff86955d28b` — serializa carregamento de ROM e exportação/diagnóstico.
  - `60b115f9730fd8cab5ec5bc55cf06915b977af69` — bloqueia diagnósticos concorrentes durante exportação e rastreamento IPL3.
- CI do commit `4e632e2` (run `41804002995`) concluiu com sucesso. CI do registro de continuidade `b915a46` (run `41804037336`) concluiu com sucesso. CI dos dois commits desta seção deve ser consultado antes de declarar a correção validada.
- Limitação: não foi adicionado teste instrumentado de Activity; a exclusão mútua foi verificada por inspeção estática e deverá ser confirmada pelo build/teste automatizado e, posteriormente, no aparelho.
- Nenhuma alteração no núcleo R4300/RSP/RDP ou na interpretação de ROM. `main` permanece intocada; branch de trabalho `dev/n64-clean-start`.
- Próxima etapa: confirmar os workflows e revisar o encerramento/estado final dos workers, inclusive se algum caminho de saída antecipada deixa botões desabilitados ou flags presas.


## 74. Revisão de estado final dos workers, exportação e carregamento — 2026-11-04

### CI confirmado da seção 73
- Run `41804378800`, commit `9cfafe5`: `completed/success`.
- Run `41804443717`, commit `60b115f`: `completed/success`.
- Run `41804473406`, commit `57db9f5` (documentação): `completed/success`.
- Portanto, os dois commits de exclusão mútua e o registro da seção 73 passaram pelo workflow Android.

### Arquivos e caminhos revistos
- `MainActivity.kt`, com foco em `runRomInstructionDiagnostic()`, `runRomInstructionDiagnosticWorker()`, `createCompleteDiagnosticButton()`, `loadRomFromUri()`, `openRomPicker()`, `onDestroy()` e transições entre telas.
- O diagnóstico IPL3/CPU mantém uma trava `romTraceInProgress` durante a execução em segundo plano e a libera em `finally`. As saídas antecipadas (CIC não reconhecido e falha ao preparar IPL3) postam atualização da interface com proteção contra Activity encerrada.
- O encerramento da Activity solicita parada cooperativa do diagnóstico IPL3/CPU, worker HLE e sessão de vídeo; não bloqueia a thread principal com `join()`.
- O exportador captura o texto da tela na thread principal, interrompe a sessão de vídeo cooperativamente e aguarda no máximo `LOG_EXPORT_STOP_TIMEOUT_MS` (5 s) antes de falhar explicitamente. Só depois lê os snapshots/coleções do barramento e CPU.
- O exportador libera a trava e recalcula os botões no callback final da UI, considerando worker de vídeo, HLE, rastreamento IPL3 e ROM selecionada. Se a Activity já foi destruída, não tenta atualizar views.
- O carregador de ROM invalida a seleção anterior durante a nova leitura, usa `romLoadGeneration` para descartar resultado obsoleto e só persiste URI depois de validar a ROM. O callback também verifica `isFinishing`/`isDestroyed`.
- Os botões de seleção/troca de ROM e os caminhos de execução/diagnóstico verificam as travas de operação; a troca de ROM não ocorre durante exportação ou diagnóstico ativo.

### Resultado da revisão
- Não foi confirmada nesta passagem uma flag de worker que permaneça presa após uma saída normal, erro ou cancelamento.
- Não foi confirmada leitura concorrente das coleções mutáveis do barramento durante o diagnóstico IPL3/CPU: a exportação recusa iniciar enquanto `romTraceInProgress` estiver ativo.
- Não foi identificada duplicação funcional nova que justifique refatoração em `MainActivity.kt`.
- Não foi feita alteração no código de produção nesta etapa; trata-se de revisão estática dos caminhos e estados. Não equivale a teste instrumentado nem a teste em aparelho.
- A revisão não prova que o botão Voltar do Android, rotação da tela ou morte do processo preservam uma sessão de emulação; a Activity é recriada e a ROM pode ser restaurada pela URI persistida, mas estado de CPU/sessão não é persistido.

### Próxima etapa recomendada
- Revisar navegação/retorno e restauração após recriação da Activity, especialmente se o usuário volta do seletor de ROM, de uma página de diagnóstico ou da tela de vídeo.
- Depois, criar uma área/tela de ferramentas para os testes sintéticos atualmente sem ponto de entrada (`runVideoTest()`, `runTrianglePipelineDemo()`, `runCpuDiagnostic()`), sem misturar esses testes com execução real de ROM e sem apresentá-los como compatibilidade de jogos.
- Avaliar testes automatizados de estado para as travas de operação antes de adicionar dependências instrumentadas.
- Branch de trabalho: `dev/n64-clean-start`. `main` permanece intocada. Nenhum jogo foi confirmado funcional em aparelho.


## 75. Área de ferramentas de diagnóstico sintético na interface — 2026-11-04

### Achado
- A inspeção da tela principal mostrou que `runVideoTest()`, `runTrianglePipelineDemo()` e `runCpuDiagnostic()` já existiam, mas não tinham entrada visível na UI.
- Os três métodos exercitam componentes internos de forma sintética e não representam boot ou compatibilidade de ROM.

### Implementação
- Adicionado botão `FERRAMENTAS DE TESTE` à tela principal.
- Adicionada tela separada com três ações:
  - `TESTE DE VÍDEO` → `runVideoTest()`.
  - `DEMONSTRAÇÃO DO PIPELINE RDP` → `runTrianglePipelineDemo()`.
  - `TESTE INTERNO DA CPU` → `runCpuDiagnostic()`.
- A tela informa explicitamente que os testes são sintéticos e não comprovam funcionamento de ROM.
- A entrada na área de ferramentas é recusada enquanto exportação, diagnóstico IPL3, HLE ou execução de vídeo estiver ativa.
- Os testes de vídeo e RDP reutilizam a tela de apresentação existente; o teste de CPU retorna ao menu principal antes de atualizar o resultado no status.
- Correção adicional de formatação do título da tela de ferramentas registrada em commit separado.

### Commits
- `53b64eb8975820b09d8b3970370e4187e275e26d` — expõe as ferramentas sintéticas na UI.
- `e01b2de74f1c90e012a8440c710fee01520cdb2b` — corrige quebras de linha do título.
- Esta documentação registra a seção 75.

### Validação e limites
- Os commits de código foram enviados para `dev/n64-clean-start`.
- O CI do commit de documentação anterior `d6d55cf` estava pendente na última consulta; verificar antes de considerar a sequência toda verde.
- CI dos commits desta seção precisa ser consultado após o push.
- Não foi executado teste instrumentado nem teste em aparelho nesta etapa.
- Nenhuma alteração foi feita em `main`, no núcleo R4300/RSP/RDP ou no fluxo de boot da ROM.
- Não afirmar que GoldenEye 007 ou qualquer outra ROM funciona por causa destes testes sintéticos.

### Próxima etapa
- Confirmar CI dos commits `53b64eb`, `e01b2de` e `d6d55cf`.
- Depois revisar navegação/retorno das telas e considerar um teste automatizado para as travas de operação.
- Branch de trabalho: `dev/n64-clean-start`.


## 76. Navegação pelo botão Voltar do Android — 2026-11-04

### CI confirmado da seção 75
- Run `41805031804`, commit `53b64eb`: `completed/success`.
- Run `41805056017`, commit `e01b2de`: `completed/success`.
- Run `41805084255`, commit `de28f37` (documentação da seção 75): estava `in_progress` na consulta desta etapa; confirmar depois.
- Os dois commits de código da tela de ferramentas passaram pelo workflow Android.

### Achado
- A Activity não implementava `onBackPressed()`.
- Várias telas internas possuem botão próprio de retorno, mas o botão físico/gestual Voltar do Android podia encerrar a Activity em vez de retornar ao menu principal.
- Isso afetava a navegação das ferramentas sintéticas e páginas de diagnóstico/vídeo; não era uma falha do núcleo de emulação.

### Correção
- Adicionado tratamento de Voltar na `MainActivity`.
- Se a tela atual não for o menu principal, o retorno troca para `mainLayout`.
- Se a tela atual for a execução experimental de ROM e o worker de vídeo estiver ativo, solicita parada cooperativa antes de retornar.
- Se já estiver no menu principal, mantém o comportamento padrão do Android e permite sair da Activity.
- O código usa o container `android.R.id.content` para distinguir o menu principal das páginas internas; não adiciona dependências.

### Limites
- Correção estática; precisa de CI e teste em aparelho para validar botão/gesto Voltar.
- Não persiste estado da CPU nem da sessão ao girar a tela ou recriar a Activity. A ROM pode ser recarregada pela URI persistida, mas uma sessão em execução é interrompida pelo `onDestroy()`.
- Não foram alterados R4300, RSP, RDP, HLE ou formato de ROM.
- Branch: `dev/n64-clean-start`; `main` permanece intocada.

### Próxima etapa
- Confirmar CI do commit de navegação e da documentação da seção 75.
- Revisar tratamento de rotação/recriação da Activity e garantir que a seleção ROM seja restaurada sem iniciar sessão automaticamente.
- Em seguida, acrescentar testes de unidade para a política de navegação/estado, se a arquitetura permitir sem dependência AndroidX adicional.


## 77. Preservação da ROM durante recriação da Activity — 2026-11-04

### Achado
- `loadRomFromUri()` removia a URI persistida no início de uma seleção nova e só a gravava novamente depois que leitura e validação terminavam.
- Se Android recriasse a Activity durante a leitura em segundo plano (por exemplo, rotação), a nova instância poderia iniciar sem URI para restaurar, perdendo a seleção em andamento.
- O callback da instância antiga já verifica `isFinishing`, `isDestroyed` e `romLoadGeneration`, evitando atualizar uma Activity encerrada; isso não preservava por si só a URI na nova instância.

### Correção
- Quando uma seleção nova é recebida, a URI candidata agora é persistida antes de começar a leitura assíncrona.
- Se a leitura falhar ou `N64RomLoader.load()` rejeitar o arquivo, a URI candidata é removida.
- Na recriação, `restoreLastRomSelection()` relê a URI e executa novamente a leitura e validação normais.
- A restauração não inicia a execução do jogo; apenas repõe a ROM validada e os controles da tela.

### Limites
- A alteração cobre rotação/recriação e também permite recuperar uma seleção após encerramento do processo, desde que a permissão persistente do SAF continue válida.
- Se o provedor revogar o acesso ou o documento desaparecer, a leitura falha e a URI é removida.
- Não foi executado teste instrumentado nem teste físico de rotação nesta etapa.
- Não persiste CPU, RAM ou sessão de emulação; uma sessão ativa continua sendo interrompida no encerramento da Activity.
- Branch: `dev/n64-clean-start`; `main` permanece intocada.

### Próxima etapa
- Confirmar CI dos commits de navegação e restauração.
- Examinar o caso de resultado cancelado no seletor e o ciclo de vida durante exportação/diagnóstico.
- Depois, decidir se vale introduzir testes unitários pequenos para a política de persistência/validação sem adicionar dependências AndroidX desnecessárias.


## 78. Auditoria do cancelamento do seletor e exportação durante encerramento — 2026-11-04

### CI confirmado
- Run `41805501396`, commit `966a9fad` (preservação da ROM): `completed/success`.
- Run `41805544338`, commit `20c85b32` (documentação seção 77): `completed/success`.
- Run `41805294704`, commit `e4b93450` (Voltar Android): `completed/success`.
- Run `41805324710`, commit `94212ce8` (documentação seção 76): `completed/success`.

### Cancelamento do seletor de ROM
- `onActivityResult()` retorna sem chamar `loadRomFromUri()` quando o resultado não é `RESULT_OK` ou não contém URI.
- Portanto, cancelar o seletor não limpa a ROM já carregada em memória e não inicia uma nova leitura.
- Quando o usuário escolhe novo documento, a permissão persistente é tentada antes do início da leitura; se não for obtida, a URI persistida anterior é removida e a seleção nova é carregada apenas para a sessão atual.
- Nenhuma falha comprovada encontrada neste caminho.

### Exportação durante encerramento da Activity
- O exportador captura o texto do relatório na thread de UI antes de iniciar a gravação.
- A operação de arquivo usa `applicationContext`, portanto não depende da View/Activity permanecer ativa para concluir a escrita.
- Toasts, reabilitação de botões e demais atualizações visuais verificam `isFinishing` e `isDestroyed`.
- O bloqueio `diagnosticExportInProgress` pertence à instância da Activity; se ela for destruída, a instância deixa de ser acessível. A exportação em segundo plano pode terminar sem callback visual na instância destruída.
- Não foi identificada necessidade comprovada de alterar o código nessa revisão.

### Limites e ressalvas
- A análise é estática do código. Não foram executados testes instrumentados, rotação física, encerramento forçado do processo ou cancelamento em aparelho.
- O seletor não cancela uma leitura já iniciada; o mecanismo `romLoadGeneration` impede callbacks obsoletos de substituir uma seleção mais recente, mas a leitura antiga pode continuar consumindo I/O até terminar.
- A substituição de uma ROM selecionada por outra ainda limpa `cartridgeBus` e `cartridgeRomBytes` no início da leitura. Se a nova seleção falhar, o aplicativo volta ao estado sem ROM carregada; isso é comportamento atual, não foi alterado.
- Branch de trabalho: `dev/n64-clean-start`. `main` permanece intocada.

### Próxima etapa
- Revisar o mecanismo `romLoadGeneration` e o comportamento quando leituras concorrentes terminam fora de ordem, incluindo consistência entre URI persistida e ROM carregada.
- Em seguida, voltar à auditoria do núcleo R4300/COP0/COP1 e criar testes de conformidade onde houver lacunas demonstráveis.


## 79. Serialização do carregamento de ROM com diagnósticos e exportação — 2026-11-04

### CI anterior
- Run 41805853873, commit ef03bd17 (seção 78): completed/success.

### Achado confirmado
- loadRomFromUri() desabilitava o botão principal durante a leitura, mas não mantinha um estado explícito de carregamento.
- Os botões de diagnóstico e exportação tinham suas próprias verificações de concorrência, porém não verificavam uma leitura de ROM em andamento.
- Como cartridgeBus era limpo no início da leitura, uma exportação iniciada nesse intervalo poderia coletar um diagnóstico parcial sem barramento; outras ferramentas também poderiam abrir durante a leitura.
- O contador romLoadGeneration protegia contra callbacks obsoletos, mas não era um bloqueio de exclusão mútua.

### Correção
- Introduzido @Volatile romLoadInProgress.
- O estado é ativado ao iniciar a leitura e desativado somente pelo callback da geração atual, após leitura e validação.
- Durante a leitura, são desabilitados os botões de diagnóstico HLE e IPL3/CPU, além do botão principal.
- A abertura das ferramentas, execução experimental da ROM, diagnósticos, exportação e nova seleção de ROM agora recusam iniciar enquanto romLoadInProgress estiver ativo.
- A rotina que restaura os controles após exportação também considera o estado de carregamento.
- O mecanismo de geração continua responsável por ignorar resultados antigos; o novo estado tem responsabilidade distinta: impedir operações concorrentes.

### Limites
- Não foi adicionada fila de carregamento nem cancelamento de I/O: uma leitura obsoleta ainda pode continuar até o provedor terminar de entregar os bytes, mas seu callback não pode substituir o resultado da geração atual.
- A verificação e alteração do estado ocorrem no fluxo da Activity; o callback de UI valida a geração antes de liberar o bloqueio.
- CI e testes automatizados deste commit devem ser confirmados após a execução.
- Não houve alteração em CPU, RSP, RDP, HLE ou branch main.

### Próxima etapa
- Confirmar CI e revisar se todas as rotas de entrada de ferramentas respeitam o novo estado.
- Depois retomar testes de conformidade COP1 (arredondamento, flags IEEE-754 e comparações) conforme a auditoria da seção 67.


## 80. COP1 — comparações signaling e quiet com NaN — 2026-11-04

### CI da etapa anterior
- Run 41806253071, commit c222af97 (seção 79): completed/success.
- Run 41806198296, commit 321193c7 (bloqueio de carregamento): completed/success.

### Achado confirmado
- A comparação COP1 calculava unordered/equal/less e atualizava FCC0, mas ignorava o bit S (bit 3) do campo condition.
- Com operando NaN, comparações signaling devem levantar Invalid Operation; comparações quiet não devem levantar Invalid apenas por unordered.
- Antes da correção, ambas eram tratadas da mesma forma: nenhuma atualizava Cause/Flag Invalid e nenhuma podia gerar FPE por essa condição.

### Correção
- Comparações com bit S=1 e operando NaN agora chamam recordFpuExceptions(Invalid).
- Com Invalid desabilitada, Cause Invalid é atualizada, Flag Invalid fica sticky e o resultado da comparação atualiza FCC0.
- Com Invalid habilitada, o mecanismo existente levanta Floating Point Exception antes da escrita de FCC0; o FCC0 anterior permanece preservado.
- Comparações quiet permanecem sem Invalid por unordered.

### Testes adicionados
- C.NGLE.S com NaN: Cause/Flag Invalid e FCC0.
- C.NGLE.S com Invalid habilitada: FPE e FCC0 preservado.
- C.UN.S quiet com NaN: FCC0 verdadeiro sem Cause/Flag Invalid.

### Commits
- Código: 82d665c803d4c474e16ae62d03c6142ab9d9dee3.
- Testes: 607b31a59201e11caa9658c95f269e7d7fae3d17.

### Limites ainda existentes no COP1
- Aritmética S/D continua usando operações Float/Double da JVM; o RM de FCR31 não é aplicado a ADD/SUB/MUL/DIV/SQRT.
- classifyBasicFpuExceptions não implementa Underflow nem Inexact; as conversões CVT/ROUND/TRUNC/CEIL/FLOOR também não registram todas as exceções IEEE-754.
- Ainda falta uma matriz de testes para signed zero, NaN signaling versus quiet payloads e arredondamento dirigido.
- A correção desta seção cobre comparações signaling, não completa a FPU.
- CI dos dois commits acima estava em execução no momento da atualização desta seção; verificar os resultados antes de considerar a etapa concluída.
- Branch main não foi alterada.

### Próxima etapa
- Verificar CI dos dois commits.
- Em seguida construir testes de conformidade para underflow/inexact e arredondamento em aritmética S/D. Não afirmar FPU completa até fechar esses casos.


## 81. COP1 — exceção Invalid em conversões para inteiro — 2026-11-04

### Achado confirmado
- As instruções de conversão para inteiro (CVT.W, CVT.L, ROUND/TRUNC/CEIL/FLOOR.W/L) já produziam o valor indefinido (0x80000000 ou 0x8000000000000000) quando o resultado arredondado era NaN, infinito ou fora da faixa representável.
- Porém, essa condição não atualizava Cause/Flag Invalid no FCR31 e não gerava FPE quando Invalid estava habilitada.
- Isso fazia o destino parecer correto em alguns casos, mas deixava o estado arquitetural da FPU incorreto.

### Correção
- A classificação de faixa inválida agora ocorre antes da escrita no registrador de destino.
- A conversão inválida chama recordFpuExceptions(Invalid).
- Com Invalid desabilitada, Cause Invalid é atualizada, Flag Invalid fica sticky e o valor indefinido arquitetural é escrito.
- Com Invalid habilitada, ocorre FPE antes da escrita no destino, preservando o valor anterior.
- A verificação contempla destinos W (32 bits) e L (64 bits), incluindo os limites assinados.

### Testes adicionados
- CVT.W.S de NaN: Cause/Flag Invalid, resultado indefinido e sem FPE quando a exceção está mascarada.
- CVT.W.S de infinito com Invalid habilitada: FPE e destino preservado.
- CVT.L.D com valor fora da faixa de 64 bits: Cause/Flag Invalid e resultado indefinido.

### Commits
- Código: cd4271081eb88b43e9c2d3bc3491715e0d4e1908.
- Testes: cfe8bc63e419d3aefe7e6c6782d4a1bdd34dea70.

### CI
- Run 41808064645 para o commit de código: em execução no momento da documentação.
- O CI precisa terminar com sucesso antes de considerar a correção validada.

### Limites
- A etapa corrige a sinalização de conversões inválidas, mas não completa Underflow/Inexact nas operações aritméticas ou conversões com perda de precisão.
- O modo RM ainda não é aplicado a ADD/SUB/MUL/DIV/SQRT.
- A análise e os testes são automatizados; ainda não há validação de GoldenEye 007 em aparelho real.
- Branch de trabalho: dev/n64-clean-start. main permanece intocada.

### Próxima etapa
- Confirmar o CI desta etapa.
- Em seguida, construir testes específicos para conversões finitas que perdem precisão e para arredondamento dirigido em operações S/D, antes de implementar lógica numérica mais ampla.


## 82. Auditoria COP0 Status e exceções privilegiadas — 2026-11-04

### Estado verificado
- A verificação de privilégio para COP0 e CACHE ocorre antes da execução: em User/Supervisor exige Status.CU0; EXL/ERL ou KSU=Kernel permitem acesso.
- COP1 e operações de memória COP1 verificam CU1 antes de tradução/endereço e efeitos de memória.
- ERET prioriza ERL/ErrorEPC; caso contrário limpa EXL e retorna por EPC. Também limpa a reserva LL.
- Entrada de exceção preserva EPC quando EXL já estava ativo, define EXL e registra Cause/BD/CE conforme o caminho.
- O MTC0 Status ainda usa o caminho genérico de escrita (sem máscara específica). O modelo também não demonstra implementação integral de todos os campos arquiteturais Status/interrupt masks/FR/CU bits.
- A instrução CACHE passa pela verificação de privilégio COP0, mas seu efeito de cache real não foi demonstrado nesta revisão; não presumir que há cache de dados/instruções modelada.

### Conclusão
- Não foi confirmada nesta etapa uma falha específica de Status que justifique alterar a escrita de MTC0 sem primeiro definir a máscara exata dos campos implementados no VR4300 e o comportamento dos bits não modelados.
- A implementação atual tem cobertura para modos Kernel/Supervisor/User, CU0, CU1, EXL/ERL, ERET e exceções básicas; isso não equivale a suporte integral ao COP0.
- Nenhuma alteração de código foi feita nesta auditoria. Evitada uma máscara improvisada que poderia apagar bits válidos ou impedir software de configurar o processador.

### Próxima investigação
- Levantar a definição dos campos Status específicos do VR4300 e confrontar com o que o emulador efetivamente consome: IE, EXL, ERL, KSU, UX/SX/KX, interrupt masks, BEV, FR, CU0/CU1 e demais campos relevantes.
- Construir matriz de testes para MTC0/MFC0 Status, transições de modo, CU0/CU1 e CACHE privilegiado, separando comportamento implementado de campos ainda sem efeito.
- Underflow/Inexact e RM em aritmética COP1 permanecem pendentes; não foram alterados nesta etapa.

### Limites
- Revisão estática dos caminhos de CPU e testes existentes; sem validação em aparelho ou ROM comercial.
- Branch: dev/n64-clean-start. main permanece intocada.


## 83. COP0 — máscara arquitetural de MTC0 Status — 2026-11-04

### Achado confirmado
- A instrução MTC0 para o registrador Status (CP0 register 12) caía no tratamento genérico e copiava os 32 bits da origem sem filtrar campos reservados ou preservar o campo TS.
- Isso era diferente dos demais registradores COP0 com regras explícitas e permitia alterar estado que o VR4300 define como reservado, constante ou mantido pelo hardware.

### Referência arquitetural
- O manual NEC VR4300 descreve Status com CU, RP, FR, RE, ITS, RSVD, BEV, TS, SR, CH, CE, DE, IM, KX/SX/UX, KSU, ERL, EXL e IE.
- O manual determina RSVD como zero, TS como estado de hardware, e CE/DE como bits sem implementação no VR4300 (hardwired zero). Para esta etapa, TS é preservado do valor anterior e os bits reservados/hardwired são descartados.
- Referência: NEC VR4300 Microprocessor User's Manual, capítulo 4, Status Register, páginas impressas 98–101: https://www.bitsavers.org/components/nec/mips/1995_NEC_VR4300_MIPS_RISC_Microprocessor_Users_Manual.pdf

### Correção e testes
- MTC0 Status agora aplica máscara explícita aos campos graváveis e preserva TS.
- Adicionados testes para escrita de todos os bits (garantindo que reservado/CE/DE não permaneçam e TS seja preservado) e tentativa de definir TS por software.
- Nenhuma máscara foi aplicada a outros registradores nesta etapa.

### Limites
- A máscara não implementa efeitos ainda ausentes dos campos Status, como RE, RP, KX/SX/UX e todos os diagnósticos; filtrar a escrita não significa que todos esses recursos estejam simulados.
- Não foi feita validação de hardware nem de ROM comercial.
- Branch: dev/n64-clean-start. main permanece intocada.


## 84. COP0 — privilégio da instrução CACHE — 2026-11-04

### Referência
- O manual NEC VR4300 especifica que CACHE depende de CP0 estar utilizável: em User ou Supervisor, CU0=0 gera Coprocessor Unusable; CU0=1 permite a instrução.
- Referências: descrição da instrução CACHE, manual VR4300, página impressa 402; exceção Coprocessor Unusable, página impressa 193. https://hack64.net/docs/VR43XX.pdf

### Revisão do código
- A verificação de privilégio já ocorre antes da execução para opcode CACHE (0x2F), usando o modo efetivo Kernel/User/Supervisor e Status.CU0.
- O executor não modela conteúdo/tag das caches; para algumas operações D-cache, mantém o efeito de invalidar a reserva LL. Isso não equivale a uma cache funcional.
- A auditoria não encontrou motivo suficiente para alterar o executor nesta etapa; foi adicionada cobertura direta do contrato de privilégio.

### Testes adicionados
- CACHE em User com CU0=0: exige exceção Coprocessor Unusable, CE=0, EPC correto e vetor geral.
- CACHE em User com CU0=1: não gera Coprocessor Unusable e avança PC.
- Os testes não comprovam tradução TLB de CACHE nem comportamento de tags/linhas; esses aspectos permanecem em aberto.

### Limites e próximos passos
- CACHE não implementa dados/tags reais, index/hit operations, writeback/fill ou estados de cache.
- Investigar separadamente a tradução do endereço e os efeitos das operações suportadas antes de ampliar a emulação.
- Branch: dev/n64-clean-start. main permanece intocada.


## 85. Correção de regressão nos testes COP0 — 2026-11-04

- Os logs das execuções 41808660142 e 41808667581 mostram que o APK foi montado, mas a suíte falhou no teste legado `cop0MoveFromAndMoveToTransferSignExtended32BitValues`.
- Causa: o teste genérico de transferência MTC0/MFC0 usava Status como se fosse um registrador de armazenamento integral. Após a introdução deliberada de semântica específica para Status, esse cenário deixou de ser um teste válido de transferência genérica.
- Correção: o teste de transferência genérica foi movido para EPC (CP0 register 14), que continua sendo um registrador de 32 bits com transferência MTC0/MFC0 sign-extended. Os testes específicos de Status permanecem responsáveis por verificar máscara, campos reservados e TS.
- Não removemos a validação de Status nem enfraquecemos seus testes.
- Esta correção ainda precisa passar pela suíte completa e CI; não declarar aprovação antecipada.
- Branch: dev/n64-clean-start. main permanece intocada.


## 86. Correção da máscara MTC0 Status conforme figura do manual VR4300 — 2026-11-04

- Consulta direta ao manual NEC VR4300, seção 6.3.5, figuras 6-5 e 6-6, páginas impressas 166–167.
- A figura 6-6 define TS (bit 21) como somente leitura; todos os demais bits do campo DS (24:16) são legíveis/escrevíveis, exceto os dois bits reservados (23 e 19), que devem ser escritos como zero e retornam zero.
- CE (bit 17) e DE (bit 16) são definidos para compatibilidade com VR4200, não usados pelo hardware VR4300, mas continuam sendo campos de software read/write. Portanto não devem ser descartados pela máscara.
- A máscara anterior 0xFF54FFFF descartava incorretamente CE e DE. Corrigida para 0xFF57FFFF, preservando TS separadamente do valor anterior.
- O teste de escrita com todos os bits setados agora espera 0xFF77FFFF quando TS já estava setado: reserved bits 23 e 19 zeram, TS mantém seu valor, CE/DE permanecem graváveis.
- Fonte primária: NEC VR4300 User's Manual, seção 6.3.5, pp. 166–167: https://hack64.net/docs/VR43XX.pdf
- A alteração aguarda suíte completa/CI. Não presumir aprovação antes do resultado.



## 87. Auditoria dos efeitos de Status em interrupções e privilégios — 2026-11-04

- Revisão dirigida de `R4300Cpu.kt`, `R4300CpuTest.kt` e dos testes de COP0 existentes na branch `dev/n64-clean-start`.
- `R4300Cpu.step()` chama `serviceMiInterrupt()` antes de buscar/executar cada instrução. A rotina atualiza Cause.IP2 quando a memória é `N64Bus`, e avalia os bits Cause.IP[7:0] em conjunto com Status.IM[7:0], Status.IE, EXL e ERL.
- O tratamento de MTC0 Status grava os campos autorizados imediatamente. Assim, a nova configuração é observada no início do próximo `step()`; não foi encontrada necessidade demonstrável de um mecanismo extra de reavaliação no próprio MTC0.
- A verificação de privilégio COP0/CACHE usa o modo efetivo: EXL/ERL forçam Kernel; caso contrário KSU=00 é Kernel e os outros códigos não são tratados como Kernel. COP0 exige CU0 fora do modo Kernel. CU1 é verificado antes de instruções COP1 e operações de memória COP1.
- Não foi comprovada falha de implementação nesta fatia; não alteramos a CPU nem a lógica de interrupções.
- Adicionado `R4300StatusInterruptTest.kt` com três casos: MTC0 Status habilita IE+IM2 e a interrupção pendente é entregue antes da próxima instrução; IP2 não é atendida com IE=0; IP2 não é atendida com IM2=0.
- CI do commit de testes `f297e3275f2d429df46eaeb7bf474d37301313c0`: **success**. Run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41809845324
- Os testes exercitam a linha IP2 já refletida em Cause usando N64Memory. A integração elétrica do MI real via N64Bus e os modos de privilégio ainda precisam de casos de integração específicos; esta etapa não declara o subsistema COP0 completo.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 88. Correção da integração SI → MI → COP0 — 2026-11-04

- Auditoria dos caminhos de interrupção encontrou uma falha concreta no SI: os dois caminhos de DMA síncrono (RDRAM→PIF e PIF→RDRAM) marcavam `SI_STATUS_INTERRUPT`, mas não ativavam `MI_INTR_SI`. Assim, a CPU não recebia Cause.IP2 por conclusão do DMA SI, mesmo com a máscara MI habilitada.
- A escrita de acknowledge em `SI_STATUS_REG` também limpava apenas o bit de status SI, deixando a origem MI sem acknowledge.
- Correção em `N64Bus.kt`: ambos os DMA agora chamam `setMiInterruptSource(MI_INTR_SI, "SI", true, "SI DMA complete")`; o acknowledge SI limpa o status e chama `setMiInterruptSource(MI_INTR_SI, "SI", false, "SI_STATUS_INTERRUPT acknowledge")`.
- Testes em `N64BusSiDmaTest.kt`: verificam que ambos os sentidos de DMA ativam MI_INTR_SI e que acknowledge limpa simultaneamente SI_STATUS_INTERRUPT e MI_INTR_SI.
- Teste de integração em `R4300MiInterruptTest.kt`: confirma que conclusão do SI DMA, com máscara SI e Status.IE/IM2 habilitados, levanta Cause.IP2 e leva a CPU ao vetor geral de exceção.
- CI da correção de código: run 41850763818 **success** — https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41850763818
- CI dos testes SI e integração CPU: run 41850785077 **success** — https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41850785077
- Limites: o modelo SI ainda executa DMA síncrono e não implementa protocolo Joybus, temporização real ou periféricos. AI continua explicitamente sem geração de interrupção; não foi alterado nesta etapa.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 89. Auditoria do Audio Interface (AI) — 2026-11-04

- Revisados os registradores AI em `N64Bus.kt`, os testes `N64BusAiRegisterTest.kt` e o ponto de avanço temporal em `N64RomVideoSession.kt`.
- Estado atual confirmado: AI_DRAM_ADDR, AI_CONTROL, AI_DACRATE e AI_BITRATE armazenam campos mascarados; AI_LEN é ignorado e sempre lê zero; AI_STATUS sempre lê zero e sua escrita não tem efeito. Não existe FIFO, contador de bytes restantes, consumo PCM, temporização de amostras nem sinalização MI_INTR_AI.
- Referência do manual de programação N64: `osAiGetStatus` documenta FIFO_FULL quando ambos os slots de DMA estão programados e DMA_BUSY durante a transferência; `osAiGetLength` informa os bytes restantes. `osAiSetNextBuffer` requer endereço alinhado em 8 bytes, comprimento múltiplo de 8, máximo de 262144 bytes e rejeita nova programação quando FIFO_FULL. Fonte: https://ultra64.ca/files/documentation/online-manuals/man-v5-2/allman52/n64man/os/osAiGetStatus.htm
- MI_INTR bit 2 é a origem AI e deve ser levantada quando não restarem amostras no fluxo de áudio; acknowledge é feito por escrita em AI_STATUS. Referência técnica adicional: https://n64.readthedocs.io/
- O avanço temporal atual `advanceViInstructions()` é uma aproximação por orçamento fixo de instruções por scanline; não há relógio mestre compartilhado ou contagem de ciclos RCP que permita derivar com segurança o consumo de amostras do AI. A sessão de ROM chama esse avanço após cada instrução CPU, o que não constitui ainda um scheduler de ciclos de hardware.
- Decisão: não implementar DMA AI instantâneo nem levantar MI_INTR_AI na escrita de AI_LEN. Isso seria temporalmente incorreto e esconderia a ausência do scheduler. Nenhuma alteração de runtime nesta etapa.
- Próxima arquitetura necessária: base de tempo RCP/CPU com unidades de ciclos documentadas; FIFO AI de dois slots; alinhamento e limite de comprimento; consumo por DACRATE; AI_LENGTH como bytes restantes; AI_STATUS FIFO_FULL/BUSY; conclusão de buffer e MI_INTR_AI; acknowledge de AI_STATUS. A saída de áudio PCM pode ser implementada em etapa separada, sem confundir interrupção de hardware com reprodução audível.
- Teste atual `N64BusAiRegisterTest.kt` cobre apenas máscaras de registradores e comportamento idle simplificado; não deve ser interpretado como cobertura de DMA/áudio real.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 90. Auditoria da distribuição de tempo CPU/VI — 2026-11-04

- Revisado `R4300Cpu.step()`: o contador COP0 Count avança atualmente com dois ciclos-base por etapa executada, inclusive quando uma interrupção MI é atendida. O próprio código comenta que não há tabela de latência por opcode e que o valor é provisório.
- Revisado `N64RomVideoSession`: após cada instrução CPU executada, chama `bus.advanceViInstructions()` uma vez; o VI usa um orçamento fixo de 5.661 instruções por scanline.
- Revisado `N64ExecutionSession`: é um executor genérico de instruções, sem relógio de hardware, temporização, áudio ou scheduler de dispositivos.
- Conclusão: existem dois avanços temporais independentes (Count no CPU e VI na sessão). Nenhum ciclo é entregue ao AI; portanto não há base temporal para decrementar AI_LEN ou concluir FIFO de áudio de forma correta. Também não se deve converter diretamente o orçamento aproximado de scanline em ciclos de áudio.
- Não foi feita alteração no runtime nesta auditoria. Preservamos o comportamento já testado de COP0 Count e VI.
- Arquitetura proposta para próxima etapa: definir uma unidade explícita de ciclo-base e um contrato de avanço emitido pelo CPU após cada etapa, incluindo entrada de interrupção/exceções; ligar o VI e futuros dispositivos a esse avanço por divisores/razões documentados; substituir gradualmente o contador aproximado do VI somente após testes de equivalência. O AI deverá consumir bytes/amostras a partir do mesmo relógio de hardware, respeitando DACRATE e fila de dois buffers.
- Critério de aceitação antes de implementar AI: testes determinísticos provando que o mesmo total de ciclos produz o mesmo avanço de dispositivos independentemente do tamanho dos lotes do executor, que pausas/cancelamentos não consomem ciclos extras e que exceções/interrupções não deixam o relógio parar.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.

## 91. Ledger centralizado de ciclos estimados da CPU — 2026-11-04

### Implementação
- Criada N64CpuCycleClock, uma contagem monotônica e local à sessão para os ciclos-base atualmente modelados pela CPU. A classe rejeita incrementos negativos e usa Math.addExact para não permitir overflow silencioso.
- R4300Cpu agora oferece setCycleAdvanceListener. O observador é chamado dentro de advanceCountCycles, depois da atualização do COP0 Count, com o mesmo delta recebido pelo divisor do Count.
- Esse ponto único cobre tanto o caminho normal (após instrução/exceção tratada) quanto a entrada de interrupção MI, que também chama advanceCountCycles(2) antes de retornar.
- N64RomVideoSession conecta o CPU ao ledger somente durante execute(), expõe executedCpuCycles() para diagnóstico/testes e remove o observador em finally ao concluir, cancelar ou falhar. Construir uma sessão sem executá-la não captura ciclos; passos de CPU posteriores à sessão também não contaminam o total.
- O ledger conta unidades determinísticas do modelo atual (dois ciclos-base por etapa), não ciclos de hardware calibrados do VR4300/RCP. Não altera o divisor do Count, a execução de instruções ou a temporização de dispositivos.

### Testes adicionados
- N64CpuCycleClockTest: avanço em lotes diferentes produz o mesmo total; avanço zero não altera o total; avanço negativo é rejeitado.
- N64RomVideoSessionTest: oito NOPs contabilizam 16 ciclos estimados e COP0 Count=8, tanto com batchSize=1 quanto batchSize=4; confirma também ledger zerado antes de execute() e que passos posteriores não são capturados.
- Os testes são determinísticos e não dependem de ROM comercial ou aparelho.

### Limites preservados
- VI continua no orçamento aproximado por instruções; não foi convertido para ciclos CPU.
- RSP continua no quantum cooperativo; não recebe custo temporal.
- AI continua sem FIFO, consumo de amostras ou MI_INTR_AI.
- Ainda não há relação calibrada entre CPU clock, RCP clock, VI scanlines e DACRATE. Portanto esta etapa não declara relógio mestre N64 nem temporização fiel.
- O ledger é recriado por sessão e não faz parte do snapshot arquitetural da CPU.

### Commits
- CPU observer: 162d5aa1815a2373a1cc3122366f4c1933405bd9.
- Ledger: 6755bbc2a7d7f8bdf93be266b71784cb1c275fa7.
- Integração da sessão: 6bb2c4f413efdf60b4ff6fbe1b4d2906e77356ad.
- Testes do ledger: 2306f188705c7ed8645beaf94384a7fa95b8572e.
- Teste de integração por lotes: 51a4d89230ddc3d3023103d3a57ca1ba38c1f846.
- Correção da expectativa COP0 Count no teste de integração: bf2f526ca50d6b826d08398cfa54dafbacaa7880.
- Ciclo de vida do observador: 224e220563ac1435dd8ad655e184d129412ac2de.
- Teste do ciclo de vida: 39f225c22774ffcf952f7d9450d0fae592ab2a0f.

### CI
- As execuções dos commits acima foram disparadas; confirmar os resultados da execução mais recente antes de considerar a etapa validada. As execuções intermediárias dos commits anteriores não substituem a validação do último commit de teste.
- Não anunciar APK novo como aprovado até a conclusão do CI.

- Branch: dev/n64-clean-start. main permanece intocada.


## 92. Auditoria de referência do relógio VI e divergência do avanço atual — 2026-11-04

### Referências de hardware consultadas
- A especificação oficial da Nintendo informa CPU a 93,75 MHz e RCP a 62,5 MHz: https://www.nintendo.com/en-gb/Hardware/Nintendo-History/Nintendo-64/Technical-Details/Technical-Details-627050.html
- A referência técnica N64 Refresh Rate Reference documenta, para NTSC, VI clock de aproximadamente 48,681818 MHz, 3.094 clocks VI por linha e 525/526 half-lines conforme modo interlaced/progressive; o VI_V_TOTAL usa contagem terminal e VI_CURRENT avança em half-lines: https://github.com/meauxdal/N64-Refresh-Rate-Reference/blob/main/N64_Timing_Reference.md
- Para NTSC interlaced, a frequência vertical de referência é 60.000/1.001 Hz; progressive é aproximadamente 59,8261 Hz. Os valores dependem do modo/região e dos registradores VI, não de um número universal de instruções CPU.

### Estado encontrado no código
- `N64RomVideoSession` chama `bus.advanceViInstructions()` uma vez depois de cada instrução CPU concluída.
- `N64Bus.advanceViInstructions` acumula unidades chamadas de instruções e usa `VI_INSTRUCTIONS_PER_SCANLINE = 5_661` para avançar uma linha.
- O modelo incrementa `viCurrentLine` em uma unidade por linha e usa `VI_V_SYNC` diretamente como total de linhas, com fallback 525. Isso não representa o contador VI_CURRENT em half-lines nem a convenção terminal documentada para VI_V_TOTAL.
- A CPU emite atualmente dois ciclos-base modelados por instrução para o Count/ledger, mas o ledger ainda não é um relógio calibrado e a latência por opcode não existe. Logo, o limiar atual de 5.661 instruções não pode ser interpretado como ciclos de hardware.

### Resultado da auditoria
- A constante de 5.661 instruções/linha não tem derivação demonstrada a partir dos clocks oficiais e dos registradores VI; deve ser tratada como aproximação provisória, não como timing NTSC fiel.
- O avanço por instrução não acompanha instruções com latências distintas, pausas, DMA ou tempos dos coprocessadores. Simplesmente trocar o argumento para ciclos estimados sem definir as conversões de clock também seria incorreto.
- Há ainda uma diferença arquitetural importante entre scanline e half-line: VI_CURRENT, VI_V_TOTAL, interlace e parity precisam ser modelados explicitamente antes de usar o contador para gerar interrupções VI confiáveis.

### Decisão e próximo passo
- Nenhuma alteração de runtime nesta auditoria. Não substituir a constante por um valor calculado sem primeiro definir a unidade do ledger, a região/modo de vídeo, a razão racional CPU↔VI, a semântica half-line/terminal-count e os efeitos de VI_CURRENT acknowledge.
- Próxima etapa: desenhar e testar um acumulador racional determinístico para conversão de ciclos CPU em clocks VI, preservando resto entre lotes; em seguida modelar VI_V_TOTAL/VI_CURRENT em half-lines e só então migrar o gatilho de MI_INTR_VI. A contagem atual fica preservada até essa migração passar testes de equivalência e regressão.
- Auditoria de duplicações/arquivos repetidos continua aberta como trilha transversal; esta seção não declara auditoria global concluída.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 93. Acumulador racional de clock CPU → VI (preparação isolada) — 2026-11-04

### Implementação
- Criada `N64RationalClockAccumulator`, utilitário genérico que converte unidades de entrada em ticks de saída por uma razão inteira numerador/denominador.
- A classe preserva o resto fracionário entre chamadas. Portanto o total convertido não depende de o avanço ocorrer em uma chamada grande ou em vários lotes menores.
- Avanços negativos e razões não positivas são rejeitados. Operações aritméticas usam `Math.multiplyExact`/`Math.addExact` para falhar explicitamente em overflow, em vez de permitir wrap silencioso.
- A razão de referência NTSC adicionada aos testes é 714/1375, correspondente à razão entre VI clock de 48,681818 MHz (1071/22 MHz) e CPU clock de 93,75 MHz (375/4 MHz).

### Testes
- 1.375 avanços unitários com a razão 714/1375 produzem exatamente 714 ticks e o mesmo resto que um único avanço de 1.375 unidades.
- Teste de resto fracionário entre chamadas, avanço zero, rejeição de valores negativos e rejeição de razões inválidas.
- Arquivos: `N64RationalClockAccumulator.kt` e `N64RationalClockAccumulatorTest.kt`.

### Limites e decisão de integração
- O acumulador ainda não está ligado a `N64Bus`, `N64RomVideoSession` ou ao MI_INTR_VI. O runtime VI existente permanece inalterado.
- A razão 714/1375 é uma referência matemática NTSC, não uma garantia de temporização fiel quando alimentada pelo ledger atual: este ledger conta dois ciclos-base estimados por etapa CPU e não possui latência por opcode nem tempos de DMA/RSP.
- Não usar ainda o resultado para avançar scanlines, half-lines, AI ou interrupções. Antes da integração é necessário definir formalmente as unidades, a região/modo de vídeo e o modelo de VI_CURRENT/VI_V_TOTAL em half-lines.
- O acumulador é uma peça de infraestrutura testada por unidade; não constitui relógio mestre do console.

### Commits
- Implementação: 77122165a7cfcada8d5dcaadc7165f65df17bc0f.
- Testes e expectativa corrigida: 9f5c778dfa67c1667d7c433cfcbc829adcb51d52.
- Documentação desta seção: a registrar após commit.

### Estado de validação
- CI da implementação/testes ainda precisa ser consultado para o HEAD atual. Não declarar a suíte aprovada nem APK atualizado até confirmar o workflow.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 94. Contrato de VI_CURRENT/VI_INTR em half-lines — 2026-11-04

### Referências técnicas consultadas
- A referência N64 Refresh Rate Reference define VI_V_TOTAL (nome libultra VI_V_SYNC_REG) como contagem terminal de half-lines: total efetivo = valor do registrador + 1. Define VI_V_CURRENT como contador de meia-linha, incrementado em 2 por scanline; em modo interlaced, o bit 0 alterna para indicar o campo. https://github.com/meauxdal/N64-Refresh-Rate-Reference/blob/main/N64_Timing_Reference.md
- O header RCP do SDK documenta VI_INTR como comparação com o half-line atual e VI_CURRENT como half-line atual; qualquer escrita em VI_CURRENT limpa a linha de interrupção. https://ultra64.ca/files/documentation/online-manuals/man/header/rcp.htm
- A referência de `osViGetCurrentLine` confirma o retorno em half-lines e descreve os padrões de valores pares em non-interlace e alternância par/ímpar entre campos em interlace. https://ultra64.ca/files/documentation/online-manuals/man/n64man/os/osViGetCurrentLine.html

### Divergências confirmadas no código e testes
- `N64Bus.advanceViInstructions` incrementa `viCurrentLine` em 1 por unidade de scanline aproximada, em vez de avançar 2 half-line units.
- A comparação atual entre `viCurrentLine` e VI_INTR usa a mesma unidade simplificada de scanline; portanto o valor de interrupção não tem a semântica half-line documentada.
- `VI_V_SYNC` é tratado como total direto de scanlines e usado como módulo, sem convenção de terminal count ou diferenciação de campo/modo.
- `N64BusVideoInterfaceTest` atualmente espera CURRENT=1 depois de uma scanline e configura VI_INTR=1/2 como número de scanlines. Esses testes caracterizam o modelo aproximado existente, não o contrato do hardware.
- Escrita em VI_CURRENT já limpa MI_INTR_VI sem alterar o contador, compatível com a função acknowledge documentada. Esse comportamento deve ser preservado e testado também no novo modelo.

### Decisão
- Não alterar runtime nesta etapa: não há ainda uma definição implementada e testada para progressão de campo interlaced/non-interlaced, wrap de terminal count e reset/phase quando VI_V_SYNC é reprogramado.
- Próxima alteração deve introduzir um pequeno modelo isolado de contador VI em half-lines, com testes para avanço de 2 unidades por scanline, bit de campo, terminal count (+1), comparação VI_INTR em half-lines, wrap e acknowledge por escrita em VI_CURRENT.
- Só depois ligar esse contador ao relógio racional. A razão CPU→VI ainda é provisória enquanto o ledger CPU usa dois ciclos estimados por etapa, sem latência por opcode e sem ciclos de DMA/RSP.
- Não interpretar os testes atuais como prova de VI fiel; eles validam apenas a aproximação antiga.
- Branch: `dev/n64-clean-start`. `main` permanece intocada.


## 95. Modelo isolado de contador VI em half-lines — 2026-11-04

### Implementação isolada
- Criada `N64ViHalfLineCounter`, sem ligação ao `N64Bus` ou à sessão de execução.
- O contador recebe o valor de `VI_V_SYNC` como terminal count; a quantidade de posições é `VI_V_SYNC + 1`.
- Cada avanço de scanline acrescenta duas unidades ao `VI_CURRENT`, que representa half-lines.
- A paridade do campo é exposta pelo bit 0 do contador. Com os valores de referência NTSC, a progressão aritmética demonstra:
  - Progressive: terminal count 525 → 526 posições; 262 avanços chegam a 524 e o seguinte volta a 0.
  - Interlaced: terminal count 524 → 525 posições; 262 avanços chegam a 524, o seguinte chega a 1 (campo ímpar), e a sequência retorna a 0 após mais 262 avanços.
- A implementação usa Long para calcular o módulo e evita overflow no caso `Int.MAX_VALUE`.

### Testes
- Avanço de duas half-lines por scanline.
- Wrap progressive e interlaced usando terminal count + 1.
- Alternância observável do bit de campo no caso interlaced.
- Fase inicial não zero.
- Rejeição de entradas negativas e limite aritmético.

### Limites e decisão
- O helper é uma unidade isolada de modelo, não um relógio VI completo.
- O helper agora oferece comparação exata `current == VI_INTR` e teste unitário dessa igualdade. Ainda não lê `VI_STATUS.SERRATE`, não acompanha escrita de `VI_V_SYNC` durante execução, não faz latch/geração de MI_INTR_VI e não implementa o acknowledge de `VI_CURRENT`.
- O runtime antigo de `N64Bus.advanceViInstructions` permanece intacto. Não integrar antes de definir a conversão temporal CPU→VI e criar testes de integração que preservem o comportamento de acknowledge e de reprogramação dos registradores.
- A paridade foi derivada da sequência documentada de half-lines e dos terminais NTSC citados na seção 94; PAL e outros modos ainda precisam de fixtures próprios.

### Commits
- Modelo isolado: c7a2a3a5c13b8330589b3df3616b8236361f426d.
- Testes iniciais: 41f6fcd719773900bd086fe76da9e541598421b7.
- Correção final da expectativa de módulo no limite inteiro (resultado correto = 1): 7cdaef3d281d28a63bf743e618e02818cad93ff3.

### Validação
- CI da implementação/testes/documentação foi consultado; a correção final de expectativa acima disparou uma nova execução. Confirmar o resultado dessa execução antes de declarar testes aprovados ou disponibilizar APK.
- Nenhuma alteração foi feita na branch `main`; o runtime VI ainda usa o avanço aproximado antigo.
- Auditoria global de duplicações de código e arquivos continua aberta.



### Complemento da seção 95 — comparador VI_INTR isolado
- Adicionado `matchesInterrupt(viIntr)`, que compara o valor amostrado de VI_CURRENT diretamente com VI_INTR em unidades de half-line.
- Teste cobre igualdade, valor diferente e comparação com valor fora da faixa observada.
- O método não gera nem limpa MI_INTR_VI; latch, timing de amostragem e acknowledge continuam reservados à integração posterior.
- Commits: comparador fc4d3df83257b975870a7a386a168b0b4a9c92d3; teste 2d493e3fd67707c4d8eaaf9cee14d1bbee5867d.


### Etapa 97 — latch VI e acknowledge isolados
- Criado `N64ViInterruptLatch`, sem conexão ao barramento.
- `sampleCurrent(currentHalfLine, interruptHalfLine)` ativa a linha pendente somente quando os valores são iguais; uma vez ativada, permanece pendente mesmo que amostras seguintes não coincidam.
- `acknowledge()` limpa somente o latch. O contador VI_CURRENT não é modificado.
- Testes cobrem match, não-match, retenção do pending, acknowledge e preservação do contador.
- A cadência de amostragem, a geração de MI_INTR_VI e a ligação com a escrita MMIO em VI_CURRENT continuam sob responsabilidade da futura integração ao N64Bus.
- Arquivos: `N64ViInterruptLatch.kt` e `N64ViInterruptLatchTest.kt`.
- Commits: implementação 0ea1072ae5dbccaa3056d8875a87c8a1fde3987e; testes 8a608ee01329f26bf671dd9bf4b7577c2c1c87ee.
- CI: consultar workflow do commit de teste antes de marcar como validado.
- Branch `dev/n64-clean-start`; `main` intacta.


### Etapa 98 — controlador VI isolado
- Composto o contador half-line e o latch em `N64ViInterruptController`, sem conectar ainda ao `N64Bus`.
- O controlador avança o contador por scanline, amostra VI_INTR após o avanço e conserva a interrupção pendente até acknowledge.
- Testes cobrem comparação no valor correto, retenção do latch após ultrapassar o comparador, acknowledge sem reset do contador, amostras sem correspondência e nova correspondência após wrap.
- A classe não converte ciclos CPU em clocks VI, não gera MI_INTR_VI diretamente e não altera registradores MMIO. A temporização aproximada atual do barramento permanece intacta.
- Arquivos: `N64ViInterruptController.kt` e `N64ViInterruptControllerTest.kt`.
- Commits: controlador e906d889c79b89618d9498eb3e4d1d33bc291721; testes f0b05bd67baad0dc9a9dfb544979011cd95b2feb.
- CI dos testes precisa ser confirmado antes de marcar esta etapa como validada.
- Branch `dev/n64-clean-start`; `main` intacta.
