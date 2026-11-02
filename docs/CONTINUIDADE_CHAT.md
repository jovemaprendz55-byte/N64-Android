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
