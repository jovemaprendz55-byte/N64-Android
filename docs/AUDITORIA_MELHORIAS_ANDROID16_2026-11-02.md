# Auditoria e melhorias do aplicativo — 2026-11-02

## Escopo e método

- Repositório: `jovemaprendz55-byte/N64-Android`.
- Branch inspecionada e alterada: `dev/n64-clean-start`. A branch `main` não foi modificada.
- Inspecionados: Manifest, `MainActivity`, carregador de ROM, memória, sessão CPU/ROM, testes do carregador, configuração Gradle e inventário de módulos/testes.
- A CI do commit `2092215` estava verde antes desta rodada. As mudanças descritas abaixo aguardam sua própria CI; não representam validação no aparelho.

## Achados

### Corrigidos nesta rodada

1. **Leitura de ROM bloqueava a interface.** `MainActivity.onActivityResult` lia até 64 MiB na thread principal. Agora a leitura e a consulta do nome do documento são feitas numa thread dedicada; o resultado volta à UI pela thread principal.
2. **A ROM selecionada não era restaurada ao reabrir o app.** O seletor agora pede permissão persistente de leitura. Quando o provedor concede essa permissão, o URI é salvo e a última ROM é restaurada na próxima abertura.
3. **Leitura do provedor dependia de um loop local e não tinha testes próprios.** `N64RomLoader.readAllBytes` centraliza a leitura limitada, não confia no tamanho informado pelo provedor, bloqueia arquivos acima do limite e trata streams que temporariamente retornam zero bytes.
4. **Seleções rápidas podiam terminar fora de ordem.** Um contador de geração impede que uma leitura antiga, terminada depois, substitua a ROM escolhida mais recentemente.
5. **Cobertura de teste do fluxo de bytes era insuficiente.** Adicionados testes para limite exato, arquivo acima do limite e leitura com retorno temporário de zero bytes.

### Limitações que continuam abertas

1. `MainActivity.kt` ainda concentra UI, execução, exportação de logs e diagnósticos; a separação em componentes deve ser incremental para evitar regressões.
2. `N64RomVideoSession` ainda não descobre nem agenda automaticamente OSTasks de libultra. Não criar tarefas por endereço presumido.
3. RSP, RDP, DPC e TLB continuam sendo implementações parciais; o caminho gráfico não é um pipeline N64 completo.
4. O RDP ainda não implementa texturas, combiner, Z-buffer, coverage e blending completo.
5. AI ainda não faz DMA/saída de áudio funcional; Joybus/PIF/controles também estão incompletos.
6. Não há teste instrumentado em dispositivo nesta rodada. CI unitária não comprova boot de ROM, imagem ou áudio no Poco C85 Android 16.
7. O manifesto não solicita permissões amplas de armazenamento; a seleção via Storage Access Framework é a abordagem correta para documentos escolhidos pelo usuário.

## Arquivos alterados

- `app/src/main/java/com/jsr/n64android/N64RomLoader.kt` — leitura limitada e reutilizável de streams.
- `app/src/test/java/com/jsr/n64android/N64RomLoaderTest.kt` — testes de limites e streams.
- `app/src/main/java/com/jsr/n64android/MainActivity.kt` — leitura fora da UI, persistência do acesso ao documento, restauração da ROM e proteção contra resultados fora de ordem.

## Próximos passos técnicos

1. Confirmar CI e instalar o APK resultante no Android 16.
2. Testar seleção de ROM, fechar/reabrir o app e confirmar restauração; testar também provedor que não conceda permissão persistente.
3. Capturar o primeiro fluxo real de escrita SP/OSTask e integrar o scheduler somente depois de localizar o descritor real em RDRAM.
4. Priorizar o primeiro opcode RSP/RDP não suportado observado numa ROM real; adicionar teste de regressão por opcode.
5. Separar `MainActivity` em controlador de ROM, controlador de sessão e exportador de diagnóstico em commits pequenos.

## Regra de validação

- Só marcar uma melhoria como aprovada depois de CI verde.
- Só marcar o jogo como funcional depois de evidência de execução real no aparelho, com imagem e, quando implementado, áudio/controles.
- Não alterar `main` sem autorização explícita.

## Auditoria da execução R4300 — 2026-11-02

### Defeito confirmado e correção enviada
- Na instrução SD (opcode 0x3F), a verificação de alinhamento chamava requireAligned(address, 8) sem marcar a operação como gravação.
- Isso classificava um endereço desalinhado como AdEL (ExcCode 4), quando a arquitetura exige AdES (ExcCode 5).
- Corrigido para requireAligned(address, 8, store = true).
- Adicionado teste de regressão sdUnalignedAddressRaisesStoreAddressError, que confirma ExcCode 5, BadVAddr, vetor de exceção e ausência de gravação parcial.
- Revalidada a suspeita inicial sobre SWR/SDR: os testes existentes percorrem todos os offsets e os casos extremos; não foi encontrada falha nessas instruções e nenhum código delas foi alterado.

### Validação e limites
- Commits de correção/teste: e4407b6 e 0a7b5f2.
- CI do HEAD 0a7b5f271c2f982e27023f5f6205305e0ead0b44 iniciada; aguardar success antes de declarar a correção aprovada ou distribuir APK.
- A CPU R4300 permanece parcial: operações aritméticas FPU/COP1, comportamento completo de COP0, exceções de bus/cache e fidelidade de ciclos ainda não estão completos.
- CI unitária não substitui execução de ROM real no Poco C85 Android 16.

## Auditoria COP1 / estado de boot HLE — 2026-11-02

### Defeito confirmado: CU1 não era respeitado
- A CPU executava instruções COP1 mesmo com Status.CU1 (bit 29) desligado.
- Corrigido no decoder: COP1 e opcodes de memória LWC1/LDC1/SWC1/SDC1 verificam CU1 antes de qualquer acesso/efeito.
- Quando CU1 está desligado, gera Coprocessor Unusable (ExcCode 11), Cause.CE=1, vetor de exceção e preserva o estado da instrução que não executou.
- Testes: cop1DisabledRaisesCoprocessorUnusableAndIdentifiesCop1, cop1DisabledInBranchDelaySlotSetsBdAndBranchEpc, cop1MemoryInstructionAlsoRequiresCu1.
- Testes COP1 que precisam executar normalmente agora ligam CU1 explicitamente.

### Integração HLE corrigida
- N64HleBoot.prepare entrega o controle no entry point pós-IPL3; o IPL3 real habilita o coprocessador antes da transferência para o jogo.
- HLE agora modela Status.CU1=1 no estado inicial da CPU, sem executar nem alegar executar IPL3.
- Teste HLE atualizado para iniciar com MFC1 real no entry point e confirmar que o handoff executa COP1 sem exceção.

### Estado e limites
- CI run 41538406114 (commit c249e6994a359a71450426d3f5ded47123f4efd9): success, build e testes unitários passaram.
- Alterações de integração HLE e seu teste estão nos commits 710b13d e f529f3f; aguardar CI do HEAD mais recente antes de distribuir APK.
- COP1 ainda é parcial: aritmética FPU, conversões, comparações, LWC1/LDC1/SWC1/SDC1 quando CU1 está habilitado e semântica completa de FCR31 não estão implementadas.
- Nenhum teste automatizado substitui execução de ROM real no aparelho Android 16.

## Auditoria COP0 — privilégio de acesso — 2026-11-02

### Defeito confirmado e correção enviada
- A decodificação COP0 aceitava MFC0/MTC0/TLBR/TLBWI/TLBWR/TLBP/ERET sem verificar o modo de privilégio.
- No VR4300, CP0 é sempre utilizável em Kernel mode; em User/Supervisor mode depende de Status.CU0. Com CU0=0, a instrução COP0 deve gerar Coprocessor Unusable (ExcCode 11, Cause.CE=0).
- A instrução CACHE (opcode 0x2F) compartilha a restrição de acesso a CP0; a verificação é feita antes do decoder para não cair incorretamente em Reserved Instruction quando CU0 está indisponível.
- A detecção de Kernel mode considera KSU=00 ou EXL/ERL ativos, conforme o manual NEC VR4300.
- Testes adicionados para User mode, Supervisor mode e acesso permitido em Kernel mode com CU0=0.

### Referência arquitetural e limites
- Referência: NEC MIPS VR4300 Microprocessor User's Manual, capítulo 4, Status Register, especialmente páginas 98–101 (CU0, KSU, EXL/ERL e modos de operação).
- Esta alteração não implementa ainda a execução funcional das operações CACHE nem a validação completa de todos os campos reservados de Status.
- Aguardando CI verde para considerar a correção aprovada.


## Auditoria do endereço 0x7000071C / janela física 64DD — 2026-11-02

### Defeito confirmado e correção enviada
- O endereço virtual 0x7000071C pertence ao segmento KUSEG (TLB-mapped). Se a TLB o traduzir para 0x0700071C, o endereço físico cai em Cartridge Domain 1 Address 1 (0x06000000–0x07FFFFFF), janela do IPL ROM do Nintendo 64DD.
- O barramento só reconhecia 0x05000000–0x05FFFFFF como janela 64DD ausente e deixava 0x06000000–0x07FFFFFF sem mapeamento. Assim, uma leitura da segunda janela podia gerar UnsupportedOperationException em vez de open bus quando o 64DD não está conectado.
- Corrigidos os intervalos e nomes: Domain 2 Address 1 = 0x05000000–0x05FFFFFF; Domain 1 Address 1 = 0x06000000–0x07FFFFFF.
- Ambas as janelas retornam 0xFF em leitura de byte (logo 0xFFFF/0xFFFFFFFF em leituras maiores) e ignoram gravações quando o 64DD está ausente.
- Testes estendidos para byte, halfword, word e gravações no endereço físico 0x0700071C, além da janela Domain 2 já coberta.

### Limite da conclusão
- A correção do barramento físico não prova, sozinha, que a TLB do estado de execução atual traduz 0x7000071C para 0x0700071C. Para fechar a trilha CPU completa, registrar o estado TLB/ASID/EntryHi/EntryLo no ponto real da falha.
- Aguardando CI deste HEAD; sem teste instrumentado em dispositivo nesta rodada.


## Auditoria do endereço 0x70000720 — 2026-11-02

- O endereço virtual 0x70000720 também pertence a KUSEG e requer tradução pela TLB; não é correto assumir seu endereço físico sem capturar a entrada TLB ativa e o ASID no instante do acesso.
- Se a tradução resultar em 0x07000720, o barramento já o inclui na janela Domain 1 Address 1 (0x06000000–0x07FFFFFF), tratada como 64DD IPL ROM ausente.
- A inspeção do código confirmou que read8 retorna 0xFF nessa janela; read16/read32 compõem os valores abertos (0xFFFF/0xFFFFFFFF), e write8 ignora gravações. write16/write32 delegam aos acessos byte a byte.
- Não foi encontrado defeito novo no mapeamento físico para 0x07000720. A lacuna era a ausência de teste explícito nesse endereço.
- Testes atualizados para cobrir leituras byte/halfword/word e gravações em 0x07000720, mantendo 0x0700071C como caso de regressão.
- Commit do teste: 2ffe1a1de8b760d7d246fe7eddd13c570d31fdbf.
- A alteração é cobertura de teste, não mudança do código de execução. Aguardar CI verde; ainda não há validação no aparelho nem confirmação da tradução TLB real de 0x70000720.


## Auditoria geral da execução R4300 — erro de barramento — 2026-11-02

### Defeito confirmado e corrigido
- A CPU deixava escapar UnsupportedOperationException quando N64Bus recebia uma leitura/gravação física sem mapeamento. Isso fazia a sessão de execução falhar no host em vez de entregar uma exceção arquitetural MIPS.
- Corrigido em R4300Cpu.step(): falha de busca de instrução gera IBE (ExcCode 6); falha de acesso a dados gera DBE (ExcCode 7).
- A correção preserva o endereço/instrução diagnósticos, preenche lastExceptionAccessType, atualiza Cause/EPC/EXL pelo caminho comum de exceção, seleciona o vetor geral e limpa o estado de delay slot pendente.
- Não grava BadVAddr para bus error; BadVAddr permanece reservado aos tipos de exceção de endereço/TLB que o atual modelo já trata.

### Testes de regressão adicionados
- Leitura de dados sem mapeamento gera DBE e mantém VA/PA/instrução em diagnóstico.
- Busca de instrução sem mapeamento gera IBE e registra FETCH.
- DBE no delay slot preserva Cause.BD e EPC apontando para a instrução de branch.

### Commits e validação
- Código: 78977193bf4a7a346767d787adf7d02e9e660461.
- Testes: a0d72640ff07dcf3058c4708daeeb2ade5783117.
- Aguardar CI do HEAD atualizado. Nenhuma validação em dispositivo foi realizada nesta etapa.


## Auditoria COP0 Count/Compare e interrupção de temporizador — 2026-11-02

### Lacuna funcional confirmada
- A inspeção de `R4300Cpu.kt` confirma que o modelo atual não incrementa COP0 Count (reg. 9), não implementa a comparação com COP0 Compare (reg. 11) e não gera a interrupção de temporizador IP7 (Cause bit 15) quando os valores coincidem.
- MTC0 para Compare (reg. 11) cai no armazenamento genérico; não limpa um pending timer interrupt porque esse estado ainda não existe.
- A amostragem atual de interrupções trata somente a linha agregada MI em Cause.IP2 (bit 10). Não há caminho equivalente para o timer interno do VR4300.
- Os testes existentes cobrem máscaras/pending de interrupção MI e alguns registradores COP0, mas não cobrem Count/Compare nem IP7.

### Impacto
- ROMs/OS que usam o temporizador COP0 para preempção, timeouts ou scheduler podem ficar presas ou apresentar comportamento temporal incorreto, mesmo quando o restante da CPU executa instruções.
- Isso é uma funcionalidade ausente do emulador, não uma falha recém-introduzida pela alteração de publicação de quadros.

### Decisão segura
- Não implementar um incremento arbitrário por instrução nesta rodada: o Count do VR4300 deriva do relógio do processador e exige uma política explícita de ciclos/clock divisor. A sessão atual contabiliza instruções, não ciclos de hardware, e branches, loads/stores e stalls de coprocessadores não têm latência modelada de forma completa.
- Próximo passo: introduzir uma fonte de ticks de CPU determinística e testável, definir a relação Count/ticks conforme o VR4300, fazer MTC0 Compare limpar IP7, gerar IP7 na igualdade Count/Compare e incluir testes de máscara Status.IM7, IE, EXL/ERL e delay slot.
- Até essa implementação e validação, documentar COP0 Count/Compare como não implementado; não afirmar compatibilidade temporal completa.

### Estado
- Nenhum código de produção foi alterado para esta lacuna, para evitar uma temporização inventada.
- Branch `dev/n64-clean-start`; `main` permanece intacta. CI da correção do fixture VI ainda está em execução.


## Revalidação após correção VI — 2026-11-03

- GitHub Actions run `41559840127`, commit `a445bd556cbb5f360b08a40bccf73e854a4ad900`: concluído com sucesso. Job `build` e etapas `Build and test` e `Upload debug APK` passaram.
- Artefato Debug publicado como `n64-android-debug-apk` (1.039.850 bytes; SHA-256 `91c6f00c10d5f7eaa70b5b2c141ce4a73218cfafde9ddbe07c68bc30dceca439`; expiração 2026-11-17).
- Reinspeção de `N64RomVideoSession`: captura VI inválida é isolada para não derrubar a sessão; publicação compara resolução e pixels; polling de RSP é limitado por quantum e somente ocorre após SP sair de HALT. Não foi comprovado defeito adicional nesse trecho nesta rodada.
- Limite: teste automatizado e geração de APK não equivalem a teste de instalação/execução em dispositivo Android 16. Esse teste ainda depende da instalação e dos logs reais no aparelho.
- COP0 Count/Compare/IP7 permanece como lacuna funcional prioritária, sem alteração de produção nesta rodada.
