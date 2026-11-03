# Plano de MVP: executar o primeiro jogo N64

Data: 2026-11-03  
Branch: `dev/n64-clean-start`  
Objetivo: obter a primeira execução real de um jogo em ROM, priorizando o caminho mínimo funcional. Depois do primeiro jogo, retomar a expansão de compatibilidade e precisão.

## Regra de prioridade
Não ampliar a cobertura de instruções R4300/COP1 por perfeccionismo enquanto houver bloqueios mais próximos do primeiro quadro do jogo. Corrigir CPU quando o jogo-alvo ou um teste de integração demonstrar que a instrução impede o boot.

## Estado já existente (inspecionado no código)
- `N64RomLoader`: reconhece Z64/V64/N64, normaliza byte order e limita ROM a 64 MiB.
- `N64HleBoot`: reconhece seeds CIC suportados, prepara parâmetros básicos e copia 1 MiB da ROM (offset 0x1000) para RDRAM 0x400; não executa IPL3/PIF e não modela toda inicialização de hardware.
- `N64RomVideoSession`: executa CPU continuamente, monitora VI e faz polling cooperativo do RSP.
- `N64ViFramebufferReader` e `N64VideoPresenterView`: convertem framebuffer VI RGBA5551/RGBA8888 e mostram Bitmap na interface Android.
- `N64DpcCommandProcessor`, parser RDP e rasterizadores: há infraestrutura inicial de comandos gráficos, mas não se deve presumir renderização completa de microcódigo comercial.
- Limitação crítica: a sessão não descobre/agenda automaticamente OSTasks libultra; o RSP só recebe quantum quando SP HALT é liberado. Áudio, timing e vários caminhos gráficos permanecem incompletos.

## Sequência de implementação orientada ao primeiro jogo
1. **Escolher ROM-alvo concreta**: obter nome/região e informações do cabeçalho; verificar formato, tamanho, entry point e CIC suportado. Não armazenar nem distribuir ROM protegida.
2. **Criar diagnóstico de boot único**: após HLE, confirmar PC inicial, instruções executadas, exceções, progresso de SP/DPC/VI e estado do framebuffer.
3. **Fechar o caminho de tarefa gráfica**: rastrear escrita de SP_STATUS, SP_PC, DMA e DPC_START/END; reconhecer o início/fim de OSTask e despachar o RSP com orçamento limitado sem travar CPU.
4. **Primeiro quadro**: priorizar o microcódigo usado pelo jogo-alvo e os comandos RDP que ele realmente emite. Começar com clear/fill e triângulos; adicionar textura/combiner conforme o trace exigir.
5. **Exibir e manter estável**: publicar VI frame sem bloquear o worker; controlar FPS, limite de instruções e logs.
6. **Teste no aparelho**: instalar APK, selecionar a ROM legal do usuário e coletar logs. Só chamar de jogo executando quando houver evidência no dispositivo; CI isolada não prova isso.

## Critério de sucesso do MVP
- ROM reconhecida e normalizada.
- HLE passa pelo entry point sem loop imediato de exceção.
- Pelo menos uma OSTask é identificada e executada.
- RDP produz framebuffer que VI consegue ler.
- Um quadro real do jogo aparece no Galaxy A15 Android 16. Um padrão de teste ou framebuffer sintético não conta como jogo funcionando.

## Perfil do jogo-alvo: GoldenEye 007

- Jogo escolhido pelo usuário em 2026-11-03: **GoldenEye 007 (N64)**.
- O código atual reconhece o seed CIC `0x3F` associado ao CIC-NUS-6102/7101 e comenta seu uso na versão USA de GoldenEye. Isso é apenas reconhecimento de CIC; não prova que o boot do jogo esteja funcional.
- Referências técnicas públicas sobre a descompilação do jogo indicam uma variante de F3D/RSP da Rare, com extensão de triângulos em lote **G_TRI4 (opcode 0xB1)**. Não tratar GoldenEye como teste garantido de F3DEX padrão. Confirmar a implementação com microcódigo/display lists da ROM e traces do emulador.
- Prioridade específica: validar cabeçalho/região/CIC da ROM do usuário; identificar OSTask e endereço de display list; identificar microcódigo carregado; então implementar o mínimo de F3D e G_TRI4 necessário, seguido dos comandos RDP realmente observados.
- Não distribuir nem pedir upload da ROM protegida. O usuário pode selecionar localmente uma cópia obtida legalmente e compartilhar apenas o resumo do cabeçalho/logs.
- Região confirmada pelo usuário em 2026-11-03: **USA**. O seed esperado para esta edição é `0x3F`; a ROM concreta ainda precisa ser validada pelo resumo de cabeçalho/checksum do app.

## Implementação de diagnóstico OSTask — 2026-11-03

- `N64Bus` agora expõe `diagnosticSpTaskDescriptor()`, que lê os 16 campos do descritor libultra convencional no offset `0x0FC0` do SP DMEM.
- Foi adicionado teste unitário que escreve os 16 campos e confirma a leitura big-endian.
- A função é somente diagnóstica: não valida a tarefa, não agenda o RSP e não executa microcódigo. O próximo passo é correlacionar o descritor com os writes de SP_STATUS/SP_PC e os DMAs reais da ROM.
- Commit de produção: `8135a0e9c8654627c5ca76290aa183f9184236e9`; teste: `981f0b64ebc44cbeafb55c17f03972fac885d499`. CI do commit de produção ainda em andamento no momento do registro.

## O que fica temporariamente em segundo plano
- Cobertura exaustiva de instruções COP1 e exceções IEEE-754.
- Precisão ciclo-a-ciclo de Count/VI/AI.
- Áudio completo, 64DD e compatibilidade ampla com vários jogos.
- Refatorações sem relação direta com o primeiro quadro.

## Próxima ação
Solicitar a região/edição da ROM GoldenEye 007 (USA, Europe/PAL ou Japan) e, quando disponível, o resumo do cabeçalho exportado pelo próprio app. Em paralelo, auditar o caminho SP DMA → OSTask → RSP e criar testes de reconhecimento de tarefas sem inventar descritores.


## Auditoria de integração OSTask — 2026-11-03

A inspeção posterior encontrou um executor SP já implementado, portanto a tarefa não é criar outro bootstrap do zero:

- `N64SpTaskBootstrap` carrega um descritor de endereço RDRAM explícito, copia-o para DMEM + 0xFC0, carrega o boot microcode na IMEM e libera SP HALT.
- `N64SpTaskRunner` executa um orçamento de instruções RSP, acompanha DMA de microcódigo para IMEM e reconhece a entrada no ucode de tarefa com base no descritor.
- `N64SpTaskLifecycle` e `N64SpTaskDpCoordinator` modelam BREAK, DP_WAIT e Full Sync.
- Testes existentes cobrem bootstrap, BREAK, orçamento esgotado, DMA do ucode de tarefa, transição de microcódigo e limpeza do listener após falha.

**Lacuna real:** `MainActivity` instancia `N64RomVideoSession`, mas essa sessão não utiliza `N64SpTaskRunner`. O caminho contínuo ainda bombeia o RSP quando o bit HALT está limpo, sem descoberta/dispatch de OSTasks libultra. O runner atual exige `taskAddress` conhecido e faz seu próprio bootstrap; chamá-lo sem identificar a sequência real do jogo pode duplicar operações ou iniciar a tarefa errada.

Próximo trabalho: observar eventos reais de SP e identificar quando o descritor de DMEM foi carregado e qual fluxo de boot o jogo está executando. Só então desenhar um despachante integrado, com testes de não duplicação, limites de orçamento, pausa/retomada e DP_WAIT. Não presumir endereço do OSTask, não limpar HALT artificialmente e não substituir o fluxo de boot da ROM sem evidência.


## Correção do leitor diagnóstico OSTask — 2026-11-03

A ordem dos dois pares finais do descritor estava invertida. Corrigido para ABI: `data_ptr/data_size` em 0x30/0x34 e `yield_data_ptr/yield_data_size` em 0x38/0x3C. O teste agora usa valores diferentes nos dois pares. Commits: `d0700f132f3a468051fe22c3445c2bb743f2fe2c` e `bb9a06c48444e98a6e8f6e5d840b1bae579009ad`. Aguardar CI antes de considerar validado.


## Observação passiva da transferência OSTask — 2026-11-03

Foi acrescentado um histórico diagnóstico que registra um DMA RDRAM→DMEM apenas quando o endereço SP inicial é 0xFC0 e o total transferido é exatamente 64 bytes. A linha registra PC da CPU, endereço do descritor e campos principais. É observação após a cópia, não inicia nem agenda o RSP. Teste cobre offset diferente, captura do caso canônico e preservação de SP HALT. Commits: `7e7eeee4ec9068f180835e174bf2dcea24f0272c` e `6313a8417d59188fe833aa02120e9e6c444962ad`. CI pendente no momento do registro.


## Ajuste de formatação do evento OSTask — 2026-11-03

A primeira execução do teste falhou porque os ponteiros no log não tinham padding de oito dígitos. Padronizado o formato de BOOT/UCODE/DATA/YIELD, mantendo a detecção passiva inalterada. Commit `dd614e6d0051bb1e256c962cbfd515d19f1fd5cd`. Aguardar CI.


## Validação do CI e estado após a auditoria — 2026-11-03 05:15 BRT

- HEAD da branch `dev/n64-clean-start`: `0e8e19d084152dd7e53b906213b593f904f14b78`.
- O workflow Android do HEAD terminou com `success`: [run 41601629455](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41601629455).
- O commit de formatação do evento OSTask `dd614e6d0051bb1e256c962cbfd515d19f1fd5cd` também tem CI `success`: [run 41601549420](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41601549420).
- Houve falhas intermediárias durante a inclusão do relatório OSTask; os commits posteriores de correção/formatação passaram. Não usar os runs antigos com falha como estado final do HEAD.
- O relatório de exportação já inclui a seção “DESCRITORES OSTASK DETECTADOS POR DMA”, alimentada pelo histórico passivo do barramento.
- A captura só reconhece DMA RDRAM→DMEM com destino SP 0xFC0 e 64 bytes. Ela registra os campos do descritor após a transferência, sem liberar SP HALT e sem iniciar o RSP.
- A árvore atual confirma que `N64RomVideoSession` continua executando a CPU e chamando `pumpRsp()` apenas em polling; quando SP HALT está ativo, não agenda tarefas. O `N64SpTaskRunner` continua sendo executor explícito para endereço de tarefa conhecido, não um scheduler ligado automaticamente à ROM.
- Nenhuma ROM foi executada nesta validação do CI; nenhum APK foi instalado/testado no aparelho. Portanto, não há evidência de GoldenEye em execução nem de primeiro quadro real.

### Próximo passo técnico obrigatório

Usar uma ROM GoldenEye 007 USA selecionada localmente pelo usuário e o relatório exportado pelo app para verificar, em ordem: (1) se o PC deixa o loop idle; (2) se há DMA canônico do descritor para DMEM 0xFC0; (3) os valores UCODE/UCODE_SIZE/BOOT/BOOT_SIZE/DRAM_STACK/DATA_PTR/OUTPUT_BUFF; (4) os DMAs RDRAM→IMEM e a entrada efetiva no microcódigo. Só depois disso implementar o despachante integrado ou a extensão F3D/G_TRI4 que os dados confirmarem. Não inventar endereço de OSTask, não forçar SP HALT e não afirmar que CI verde significa jogo funcionando.


## Auditoria do caminho de execução Android — 2026-11-03

Foi inspecionado o fluxo real chamado pelo botão de iniciar ROM, além da CPU e da sessão:

- `MainActivity.startRomVideoExecution()` cria uma thread dedicada `N64-ROM-VIDEO-CPU`, prepara CPU/HLE e executa `N64RomVideoSession.execute()`. O código não executa a CPU na thread principal da interface.
- `N64ExecutionSession` conta instruções, permite cancelamento cooperativo e converte exceções de host em estado `FAILED` com mensagem; `MainActivity` mostra a falha na tela. O worker não mascara o estado da sessão.
- `N64VideoPresenterView.submitFrame()` recebe frames do worker por mailbox e publica a atualização com `View.post`, sem adicionar uma fila ilimitada de frames.
- Portanto, a auditoria não encontrou evidência de que o Android esteja simplesmente deixando de iniciar a thread ou bloqueando a interface como causa do relatório anterior.
- A falha observada no log anterior foi um TLB miss de leitura em VA `0x7F0BCC28`, seguido por execução em loop no caminho de exceção. Isso é uma falha do estado/fluxo de execução do emulador/guest, não um erro de renderização Android comprovado.
- Causa provável ainda em aberto: o HLE pula o IPL3/PIF e entra diretamente no entry point com TLB habilitada, sem reproduzir a inicialização completa de memória e estruturas de tradução que o jogo espera. O código trata o TLB miss como exceção arquitetural, mas não há evidência de que o handler consiga resolver esse miss com o estado HLE atual. Não corrigir isso desligando a TLB nem mapeando o endereço arbitrariamente.
- A sessão ainda não agenda OSTasks libultra automaticamente. O polling do RSP só o executa quando o software libera SP HALT; no log analisado RSP=0 e VI=0, então a ausência de quadro ocorre antes de qualquer evidência de renderização.
- Limitação secundária: se o RSP lançar uma exceção de host, `pumpRsp()` a registra e relança, fazendo a sessão CPU terminar em `FAILED`. Isso deve ser revisto quando o RSP começar a executar tarefas reais; não explica o log atual, no qual nenhuma instrução RSP foi executada.
- CI do teste TLB do GoldenEye passou: [run 41604378811](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41604378811), job `build` success, incluindo Build and test e Upload debug APK. Isso valida compilação/testes automatizados, não a execução da ROM no aparelho.
- Aparelho de teste correto: **Poco C85 / Android 16**. Uma referência anterior a Galaxy A15 estava incorreta.

### Próximo passo da correção

Instrumentar o caminho de boot HLE e o primeiro handler TLB com checkpoints determinísticos: PC/opcode, EPC, Cause, Status, BadVAddr, EntryHi, ASID, entradas TLB válidas e evolução do PC após cada instrução do handler. Comparar com as escritas TLBWI/TLBWR e com a inicialização das tabelas de página feitas pelo próprio jogo. Só depois decidir se o erro está no handoff HLE, na CPU/TLB ou na execução do handler. Em seguida, testar o fluxo com uma ROM local e confirmar pelo log do Poco C85.
