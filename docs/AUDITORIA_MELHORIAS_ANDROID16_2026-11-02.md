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
