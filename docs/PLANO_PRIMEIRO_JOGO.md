> **Aviso de escopo — 2026-11-06:** este documento contém registros históricos de uma arquitetura anterior e não deve ser usado como especificação de implementação da versão limpa. Não migrar nem reutilizar aqueles módulos/caminhos. O estado e o roteiro vigentes exclusivamente para a versão limpa estão em [ESTADO_ATUAL_VERSAO_LIMPA_2026-11-06.md](ESTADO_ATUAL_VERSAO_LIMPA_2026-11-06.md). Para continuidade, seguir também o adendo mais recente em [CONTINUIDADE_CHAT.md](CONTINUIDADE_CHAT.md).

---

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
- Um quadro real do jogo aparece no Poco C85 Android 16. Um padrão de teste ou framebuffer sintético não conta como jogo funcionando.

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


## Revisão adicional da CPU/TLB — 2026-11-03

Revisão do código de tradução e exceção após a CI verde:

- O endereço reportado `0x7F0BCC28` pertence a KUSEG (abaixo de `0x80000000`), portanto deve passar pela TLB quando `tlbEnabled=true`; não é endereço KSEG0/KSEG1 de acesso direto.
- `physicalAddress()` faz essa distinção e usa ASID de EntryHi para a tradução.
- O caminho de TLB miss atual salva BadVAddr, atualiza Context/EntryHi, define ExcCode=2 para leitura, seta EXL e seleciona o refill vector `0x80000000` quando BEV/EXL estão limpos. O teste específico confirma esse comportamento.
- A revisão de `TLBP`, `TLBR`, `TLBWI`, `TLBWR`, EntryLo, PageMask, ASID e seleção even/odd não revelou, nesta inspeção estática, um defeito comprovado que explique sozinho o loop do GoldenEye. Não alterar essas regras sem evidência de divergência no log.
- O ponto ainda suspeito é o estado de boot: `N64HleBoot.prepare()` limpa COP0/TLB via `cpu.reset()`, habilita TLB imediatamente e não instala entradas iniciais nem um handler de refill. Assim, um primeiro acesso KUSEG sem mapeamento produz corretamente um miss; o que ainda falta provar é se o código do jogo já deveria ter preparado o mapeamento/handler antes desse acesso, ou se o HLE pulou uma etapa necessária do IPL3.
- O exportador já inclui o histórico TLBWI/TLBWR, a rotina observada de setup PTE, a trilha anterior ao primeiro miss, as instruções após a exceção, acessos VA/PA e o snapshot completo da TLB. Esses campos devem ser usados para decidir o próximo ajuste, em vez de criar mapeamentos artificiais.
- A CI do commit de auditoria `f4be9ce9610566c1f12a766d48b21057e8bd919a` concluiu com sucesso: [run 41604630974](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41604630974). Nenhum teste automatizado substitui a execução da ROM no Poco C85.

### Próximo passo objetivo

Obter uma exportação feita pelo botão **executar ROM** na versão mais recente e verificar, no mesmo relatório: se existe TLBWI/TLBWR antes do fault; quais entradas estavam válidas; o conteúdo executado no vetor `0x80000000`; se o handler faz TLBP/TLBWR/TLBWI; e o primeiro PC que repete. Se não houver nenhuma escrita TLB antes do acesso KUSEG, investigar o estado inicial criado pelo HLE; se houver, confrontar VPN2/ASID/PageMask/PFN da entrada com `0x7F0BCC28`. Não desligar TLB nem inserir uma entrada presumida.


## Plano de evolução do HLE para handoff equivalente ao IPL3 — 2026-11-03

Objetivo confirmado pelo usuário: o emulador deve iniciar jogos sem depender da PIF ROM nem executar o IPL3 original. O HLE deve reproduzir o estado observável que a sequência de boot deixaria para o código do jogo, e não apenas saltar para o entry point.

### Auditoria do estado atual de N64HleBoot.prepare()

Confirmado diretamente no código e nos testes:

- Detecta apenas os CIC/seed atualmente suportados (6102/7101 e 6105/7105).
- Lê o entry point do cabeçalho e exige KSEG0 dentro da RDRAM.
- Copia 1 MiB do cartucho, ROM offset 0x1000, para RDRAM física 0x400.
- Grava os parâmetros de boot em RDRAM física 0x300 (TV type, ROM type/base, reset type, CIC ID, IPL2 version e tamanho de RDRAM).
- Executa cpu.reset(), habilita CU1 em Status, habilita a tradução TLB, configura $s3-$s7, $sp e PC.
- Não usa PIF ROM nem copia/executa IPL3. Os testes sintéticos confirmam esse contrato reduzido.

### Lacunas que impedem chamar o boot atual de equivalente ao IPL3

- cpu.reset() zera COP0 e a TLB; o HLE não estabelece um estado COP0/TLB de handoff comprovadamente equivalente ao IPL3.
- O HLE habilita a TLB vazia, sem evidência de que esse seja o estado correto para a edição GoldenEye USA no instante do handoff.
- A inicialização de RDRAM e dos registradores de hardware (RI, MI, SP, PI, SI, VI e AI) é parcial ou ausente; os modelos do barramento têm vários registradores em defaults simplificados.
- O conjunto de registradores gerais inicializados é pequeno; não há contrato validado para os demais GPR, HI/LO, FPR/FCR e estado de exceção.
- Não há uma sequência explícita, testada e documentada das operações que o IPL3 executaria antes de transferir controle ao entry point.
- O stack atual é definido como 0xFFFFFFFFA07FFFE0; esse valor ainda precisa ser confrontado com evidência do handoff real e não deve ser tratado como verdade universal para todos os CIC/jogos.
- A detecção do CIC é baseada na soma do IPL3 da ROM. O HLE usa esse resultado como seed/ID, mas ainda não reproduz todas as diferenças de inicialização específicas de cada CIC.

### Estratégia segura de implementação

1. Criar um contrato explícito de estado de handoff HLE, separado da lógica de cópia da ROM. Cada campo deve ter origem técnica documentada e teste próprio.
2. Capturar, antes e depois do handoff, GPR, HI/LO, COP0, TLB, PC, memória baixa e snapshots dos registradores MMIO. Isso permitirá comparar o estado inicial e detectar campos deixados acidentalmente zerados.
3. Determinar a sequência e os valores de inicialização para o CIC 6102/7101 usado pelo GoldenEye USA a partir de documentação técnica/referências de implementação verificáveis. Não extrapolar valores de outro CIC ou de outro jogo.
4. Implementar as etapas em ordem: estado RDRAM/controladores; parâmetros de boot em memória; estado CPU/COP0/TLB; registradores de uso geral; handoff ao entry point.
5. Adicionar testes de unidade para cada etapa e testes de regressão para garantir que PIF ROM continue opcional e IPL3 não seja executado.
6. Rodar GoldenEye USA e usar o primeiro fault como evidência: verificar se há TLBWI/TLBWR antes do acesso 0x7F0BCC28, se o vetor de refill contém código válido e como o PC progride. Não criar mapeamentos presumidos.
7. Só declarar o HLE equivalente ao handoff quando os valores e a sequência estiverem fundamentados e os testes cobrirem o contrato. Só declarar o jogo funcional após quadro real no Poco C85 Android 16.

### Regra de engenharia

Não copiar tabelas de boot de outro emulador sem entender a convenção de endereço/estado; não desabilitar a TLB para esconder faults; não mapear 0x7F0BCC28 por conveniência; não usar firmware/PIF/IPL3 original como dependência. O objetivo é um HLE próprio, reproduzível e testável.

### Estado desta etapa

Auditoria e plano registrados. Nenhum valor de registrador foi alterado nesta etapa, porque a inspeção atual não fornece evidência suficiente para afirmar quais valores exatos o IPL3 deixa no handoff do GoldenEye USA.

## Auditoria de fonte do IPL3 CIC 6102/7101 — 2026-11-03

Foi consultada a disassembly pública de referência `decompals/N64-IPL/src/ipl3.s`, variante `ipl3.6102_7101`, além do procedimento de boot descrito no manual oficial de programação do Nintendo 64.

### O que a fonte confirma

- O IPL3 6102 não se limita a copiar 1 MiB e saltar para o entry point. Antes disso, configura RI/RDRAM, detecta módulos de memória, configura timings/refresh, calcula e grava o tamanho real de RDRAM em `0x80000318`, inicializa tags de I-cache/D-cache e só então continua o carregamento.
- O IPL3 inicializa `Cause`, `Count` e `Compare` no início da rotina. A inicialização de CP0 também faz parte dos estágios anteriores de boot (IPL1/PIF), que o nosso HLE atualmente pula.
- O código 6102 guarda temporariamente `s3-s7` durante a configuração de RDRAM e os restaura antes de seguir. Isso confirma que esses registradores carregam os parâmetros recebidos do IPL2, mas não basta para deduzir todos os valores de GPR no instante final.
- A inicialização de cache é explícita: zera `TagLo/TagHi`, percorre linhas da I-cache com operação de index store tag e percorre D-cache com operação de index store tag no cold boot. O caminho NMI é diferente.
- O tamanho de RDRAM não deve ser presumido como 8 MiB sem modelar a detecção; 8 MiB é o valor esperado para o cenário com Expansion Pak, não uma constante universal do IPL3.

### Divergências concretas no HLE atual

- `N64HleBoot.prepare()` copia 1 MiB e grava o tamanho de RDRAM como 8 MiB, mas não reproduz o estado dos controladores RI/RDRAM nem a detecção da memória.
- `R4300Cpu.reset()` zera todos os registradores COP0 e a TLB; em seguida o HLE habilita CU1 e liga `tlbEnabled` com uma TLB vazia. Ainda não há prova de que esse seja o estado correto no handoff do IPL3 6102.
- O HLE não modela operações de cache do R4300. O núcleo também declara que operações CACHE e o modelo completo de cache ainda não estão implementados.
- O barramento mantém defaults simplificados para SP/MI/PI/SI/AI/RI. A disassembly mostra que o boot executa operações reais nesses controladores antes do handoff.
- O HLE define `$sp = 0xFFFFFFFFA07FFFE0`. A disassembly usa temporariamente uma pilha derivada do SP inicial em RSP memory e restaura a pilha antes de prosseguir; precisamos rastrear o valor final exato no IPL2/transferência antes de declarar o stack atual correto.

### Decisão técnica desta etapa

Não alterar ainda o valor da TLB, Status, stack ou registradores MMIO por aproximação. A disassembly de IPL3 mostra que o HLE atual pula várias fases essenciais, mas o estado de entrada de IPL3 depende também do IPL1/IPL2. O próximo passo é extrair e documentar o contrato de entrada/saída do IPL3 6102 e o estado CP0/RSP deixado pelo IPL2; depois criar um perfil HLE com operações explícitas e testes.

Referências:
- Disassembly correspondente: https://github.com/decompals/N64-IPL/blob/main/src/ipl3.s
- Repositório de disassemblies IPL1/IPL2/IPL3: https://github.com/decompals/N64-IPL
- Manual de programação N64, procedimento de boot: https://ultra64.ca/files/documentation/online-manuals/man/pro-man/pro06/06-10.html

## Correção do estado inicial de CPU com base em IPL1/IPL2 — 2026-11-03

A leitura da disassembly `src/pifrom.s` esclareceu o estado herdado pelo IPL3 6102:

- IPL1 escreve `Status = SR_CU0 | SR_CU1 | SR_FR = 0x34000000`.
- IPL1 escreve `Config = 0x0005E463` (perfil `CONFIG_NONCOHRNT`).
- IPL1 define `$sp = PHYS_TO_K1(SP_IMEM_END - 0xF) = 0xA4001FF0`; o IPL3 usa essa pilha temporariamente e restaura o valor antes de prosseguir para o jogo.
- IPL2 fornece `s3-s7`; para cartucho NTSC-U com CIC 6102, o perfil inclui ROM type 0, TV type 1, reset type 0, seed 0x3F e versão 0.
- IPL3 zera Cause, Count e Compare no começo da rotina.

### Alteração aplicada no HLE

- `N64HleBoot.INITIAL_STATUS` agora reproduz 0x34000000, em vez de ligar somente CU1.
- `N64HleBoot.INITIAL_CONFIG` agora define 0x0005E463.
- `INITIAL_STACK` foi corrigido de `0xFFFFFFFFA07FFFE0` para `0xFFFFFFFFA4001FF0`.
- O teste HLE agora verifica Status, Config e stack exatos.
- A TLB continua habilitada e vazia; não foi criada nenhuma entrada artificial. O estado do MMU ainda será comparado com o caminho de boot e o primeiro acesso KUSEG do GoldenEye.

Commits de código/teste:
- `e7195b85da0b7b5ffed0f7a2b626f95635cf3c8f` — estado CP0 e stack no HLE.
- `f1390015b7680eb0d69d701badce7726d7d2553f` — asserts exatos de Status/Config.
- Correções de formatação intermediárias: `38d9bcffe89e7040e32b2b46bf13287d8245c72f` e `dd42fff048a0b2dcb7372f526f3453caacc65a6d`.

Ainda não declarar boot equivalente: a inicialização de RI/RDRAM, caches, dispositivos e checksum da imagem de 1 MiB segue parcial. CI do commit final deve ser conferida antes de instalar APK.


## Atualização HLE CIC 6102 e validação de CI — 2026-11-03 06:00 BRT

Esta seção atualiza/corrige descrições anteriores do documento que ainda mencionavam destino fixo 0x400.

- `N64RomChecksum` calcula CRC1/CRC2 CIC 6102/7101 sobre o payload de 1 MiB. O teste de referência para payload totalmente zerado espera CRC1 `0xF8CA4DDC` e CRC2 `0x303A4DDC`.
- `N64HleBoot.prepare()` valida esses CRCs contra o cabeçalho da ROM antes de alterar CPU ou RDRAM. CRC inválido rejeita o boot sem mutação. Para seed 0x91 (CIC 6105), a validação ainda não está implementada e `checksumValidated=false` permanece explícito.
- O payload da ROM em offset `0x1000` é copiado para o endereço físico derivado do entry point KSEG0 do cabeçalho, e não necessariamente para `0x400`. O valor padrão 0x80000400 continua resultando em destino físico 0x400.
- O teste diagnóstico também foi atualizado para fornecer CRC válido; isso corrigiu a falha que apareceu após a introdução da validação de checksum.
- Commit `c128a26b56d96ba8c6cf5dcbc4edfae5118c6478`: fixture diagnóstico compatível com CRC 6102. CI verde: [run 41606903787](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41606903787). Build, testes e upload do APK debug concluíram com sucesso; o artefato foi publicado com nome `n64-android-debug-apk`.
- Teste adicional cobre entry point alternativo `0x80001000` e confirma cópia para RDRAM física `0x1000`. Commits: `519c7779a3b5105a67c0a9c2c7548a961b89fc9c` e limpeza `75ac1746cd9995c934f46929da49aeee941124b2`.

**Estado:** HLE ainda não é equivalente completo ao IPL3: RDRAM calibration/controladores, caches, inicialização MMIO e caminho CIC 6105 continuam incompletos. CI verde comprova compilação/testes automatizados, não execução do GoldenEye no Poco C85.


## Auditoria de lacunas de hardware do HLE — 2026-11-03

Após a CI verde do commit `08d3414d98576a0e48eb48235d15a1f53cad27c3`, foi feita uma nova comparação direta entre `N64HleBoot.kt`, `N64Bus.kt`, `R4300Cpu.kt` e a disassembly pública de `decompals/N64-IPL/src/ipl3.s`.

### Evidências confirmadas na disassembly 6102/7101

- No início do IPL3, o código zera COP0 Cause, Count e Compare.
- A primeira decisão de boot lê `RI_SELECT`: valor diferente de zero desvia para a rotina NMI; zero segue a inicialização completa de RDRAM. Portanto, o estado de RI_SELECT não pode ser escolhido por conveniência no HLE.
- O caminho de cold boot configura RI em modo de controle automático, espera a estabilização, zera RI_CURRENT_LOAD, configura RI_SELECT e RI_MODE, e depois configura MI_INIT_MODE antes de programar os delays da RDRAM.
- A escrita em RDRAM_DELAY depende da sequência imediatamente anterior de MI_INIT_MODE e da rotação de 16 bits descrita nos comentários da própria disassembly. Não é correto substituir isso por apenas gravar um valor final sem modelar a semântica relevante.
- O IPL3 detecta módulos RDRAM, consulta DEVICE_TYPE e MANUFACTURER, escolhe RAS_INTERVAL, configura CC e calcula o tamanho de memória. O tamanho é escrito em `0x80000318` no perfil normal 6102/7101.
- Ao final do cold boot, IPL3 inicializa I-cache com Index Store Tag e D-cache com Index Store Tag. O caminho NMI é diferente: para D-cache usa Index Writeback Invalidate.
- A rotina usa leituras reais em endereços de RDRAM durante detecção e teste de módulos. Uma constante fixa de 8 MiB não substitui essa detecção em um modelo que pretenda suportar configurações de memória diferentes.

### Divergências observadas no código atual

- `N64Bus` guarda RI em um array simples: escritas em RI apenas substituem a palavra correspondente; não há semântica de seleção, modo, refresh ou interação com controladores RDRAM.
- O valor de reset atualmente declarado para RI_SELECT é `0x14`. Como a disassembly testa RI_SELECT antes de decidir entre cold boot e NMI, esse valor precisa ser confrontado com o estado de hardware esperado e com o perfil HLE final. Não foi alterado nesta auditoria.
- O HLE grava `N64Memory.DEFAULT_RAM_SIZE` em osMemSize e não executa a detecção de módulos.
- `R4300Cpu.kt` declara explicitamente que operações de cache não estão implementadas. O HLE pula o IPL3, logo também pula as operações de cache que seriam executadas antes do entry point; o estado de cache resultante ainda não é representado.
- A implementação atual de HLE não modela a sequência de MI_INIT_MODE/RDRAM_DELAY nem a janela de escrita broadcast em registradores RDRAM.

### Próxima implementação segura

1. Definir um perfil explícito de memória para o primeiro alvo (GoldenEye 007 USA no cenário de 8 MiB), sem afirmar que o perfil é universal.
2. Documentar os valores finais de RI/RDRAM usados pelo IPL3 6102 para esse perfil e a origem de cada valor.
3. Implementar no barramento apenas as semânticas necessárias e testáveis: RI, MI init-mode, acesso aos registradores RDRAM e detecção/resultado de tamanho de memória. Não simular delays por laços de CPU; modelar o estado final observável do HLE com uma API de inicialização explícita.
4. Representar a invalidação inicial das caches no estado da CPU ou implementar as operações CACHE necessárias com testes. Não transformar indiscriminadamente toda instrução CACHE em NOP.
5. Manter a diferença cold boot/NMI explícita. Não alterar RI_SELECT_RESET até validar se o valor é reset de hardware, estado pós-inicialização ou apenas placeholder do barramento.
6. Adicionar testes para o perfil de 8 MiB e para rejeitar/identificar perfis de memória não suportados. Depois rodar CI e testar a ROM localmente.

Referências técnicas:
- Disassembly IPL3: https://github.com/decompals/N64-IPL/blob/main/src/ipl3.s
- Disassembly IPL1/IPL2: https://github.com/decompals/N64-IPL/blob/main/src/pifrom.s
- Visão geral da sequência de boot: https://www.copetti.org/writings/consoles/nintendo-64/
- Notas técnicas sobre a sequência IPL3 e parâmetros de boot: https://reverseengineering.wiki/index.php/Platform:Nintendo_64

**Estado desta auditoria:** somente inspeção e documentação. Nenhum valor de RI, RDRAM, TLB, cache ou CPU foi alterado. O APK do commit `08d3414` foi publicado e a CI passou, mas ainda não foi executado no Poco C85 e não há evidência de GoldenEye em funcionamento.


### Correção da interpretação de RI_SELECT — 2026-11-03

A leitura mais detalhada da sequência de `ipl3.s` esclarece uma ambiguidade da auditoria acima:

- No início do IPL3, `RI_SELECT != 0` realmente seleciona o caminho NMI.
- No cold boot normal, o IPL3 escreve `0x14` em RI_SELECT **depois** de configurar RI_CONFIG e zerar RI_CURRENT_LOAD. Portanto, `0x14` é o valor produzido pela sequência de cold boot, não o valor de reset de hardware.
- O `N64Bus` atual inicializa RI_SELECT em `0x14`. Isso pode representar um estado pós-IPL3 para o HLE, mas não pode ser tratado como reset físico caso futuramente executemos IPL3 real. A constante e seu comentário precisam distinguir explicitamente estado de reset e estado de handoff.
- A sequência de cold boot também escreve `RI_CONFIG=0x40`, `RI_MODE=0` e depois `RI_MODE=0x0E`; escreve `MI_INIT_MODE=MI_SET_INIT|15` imediatamente antes de `RDRAM_DELAY`; o valor passado ao registrador RDRAM_DELAY é `ROT16(RDRAM_DELAY(5,7,3,1))`. Em seguida zera RDRAM_REF_ROW e inicia a enumeração de módulos.
- A rotina enumera módulos consultando MI_VERSION para escolher espaçamento de registradores RDRAM (512 bytes em RCP 1.0 ou 1024 bytes em RCP 2.0), configura CC por módulo, lê DEVICE_TYPE e MANUFACTURER, ajusta RAS_INTERVAL, remapeia os módulos e só então calcula osMemSize. Assim, não existe um único conjunto completo de valores de RDRAM que possa ser aplicado com segurança sem modelar o perfil dos módulos.

**Decisão:** não alterar RI_SELECT_RESET nem preencher RI/RDRAM com constantes presumidas nesta etapa. A próxima mudança de código deve separar claramente `powerOnResetState` de `postIpl3HandoffState` (ou API equivalente) e cobrir os dois com testes. O perfil GoldenEye USA deve ser derivado de dados de hardware suportados, não de um valor arbitrário de 8 MiB.

## Implementação RI_SELECT e CI verde — 2026-11-03 06:07 BRT

A separação entre reset físico e handoff HLE foi implementada e validada:

- Um N64Bus recém-criado inicia RI_SELECT em RI_SELECT_POWER_ON_RESET = 0x00000000.
- O método applyCic6102PostIpl3RiHandoff() aplica RI_SELECT_CIC6102_POST_IPL3 = 0x00000014 somente para o seed CIC 6102/7101 durante N64HleBoot.prepare().
- O caminho CIC 6105/7105 não recebe esse handoff específico.
- Testes cobrem o valor inicial do barramento e o valor aplicado pelo HLE CIC 6102.
- O teste de snapshot MMIO foi atualizado para esperar o valor de reset físico (zero) em um barramento novo. Isso corrige a falha de regressão sem mascarar a diferença entre reset e handoff.

Commits relevantes:
- cf5db6e252a65c2ae7188ec568b004b63bdef08c — separa reset e handoff.
- 769605e52fb7f9192c814b78dadca10c7ca33841 — aplica handoff CIC 6102 no HLE.
- 43b2e9887d635b9985c25cdb12089810d8b00951 — testes dos dois estados.
- b72b0d11f60017b9b63925dd423755177e8f78dd — corrige expectativa de teste de RI.
- 97ebe628f3269d6ab1297ccf2f189ae7fd6ea89d — atualiza teste de snapshot MMIO.

Validação final: workflow Android run 41608340511 (https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41608340511) concluiu com success. As etapas Build and test e Upload debug APK passaram; o artefato n64-android-debug-apk foi publicado (1.049.362 bytes; SHA-256 a46883b80cc1a92b684422b2c7d8d1970fd5995bdcfa435f3b7a95add2887085). O artefato expira em 17/11/2026.

Limites mantidos: isso valida compilação e testes automatizados, não o boot de GoldenEye no Poco C85. A implementação cobre apenas o RI_SELECT inicial e o valor de handoff identificado; não modela RI_CONFIG/RI_MODE, registradores RDRAM, enumeração de módulos, temporização, caches ou o restante da sequência IPL3.

### Próxima etapa técnica

Continuar a auditoria do caminho mínimo para o primeiro quadro: instrumentar/validar o encadeamento SP DMA → descritor OSTask → DMA de microcódigo → execução RSP, aproveitando o diagnóstico passivo existente. Não aplicar um perfil RDRAM presumido nem forçar SP HALT. A validação final do GoldenEye USA ainda depende do relatório gerado no aparelho com a ROM local do usuário.