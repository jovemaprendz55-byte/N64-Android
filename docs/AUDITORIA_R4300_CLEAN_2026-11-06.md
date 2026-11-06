# Auditoria do núcleo R4300 — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: somente `app/src/clean/java` e `app/src/cleanTest/java`.

## 1. Arquivos inspecionados

Produção:
- `N64HleR4300CpuState.kt`
- `N64HleR4300InstructionDecoder.kt`
- `N64HleR4300InstructionStepper.kt`
- `N64HleR4300ImmediateExecutor.kt`
- `N64HleR4300SpecialExecutor.kt`
- `N64HleR4300MemoryExecutor.kt`
- `N64HleR4300Cop0Executor.kt`
- `N64HleR4300ExceptionInstructionClassifier.kt`
- `N64HleR4300ExecutionSession.kt`

Testes:
- `N64HleR4300InstructionStepperTest.kt`
- `N64HleR4300ExceptionInstructionClassifierTest.kt`
- demais testes específicos dos executores e estado devem ser ampliados conforme as lacunas abaixo.

## 2. Capacidades confirmadas no código

- Estado GPR de 64 bits com GPR0 constante zero e rastreamento de registradores conhecidos.
- HI/LO podem permanecer desconhecidos até serem explicitamente inicializados ou escritos.
- COP0 é armazenado como valores de 32 bits, com estado conhecido/desconhecido.
- Decoder separa extração de campos da execução; reconhecer uma operação no decoder não significa que ela esteja implementada.
- SPECIAL inclui operações de deslocamento, aritmética, lógica, comparação, HI/LO, multiplicação/divisão e transferências de controle.
- Executor imediato cobre ADDI/ADDIU, DADDI/DADDIU, ANDI/ORI/XORI, SLTI/SLTIU e LUI.
- Executor de memória cobre loads/stores inteiros e operações de merge, limitado ao espaço RDRAM injetado.
- COP0 executor cobre apenas MFC0 e MTC0.
- Stepper integra execução, delay slot, exceções aritméticas, faults de memória e faults de fetch.
- ExecutionSession executa orçamento finito de instruções e preserva diagnósticos de falha.

## 3. Lacuna prioritária: despacho de instrução não suportada

No método `executeNonControl()` do `N64HleR4300InstructionStepper`, o último ramo chama `immediateExecutor.execute(instruction, state)` para qualquer opcode que não tenha sido tratado antes.

Isso produz erro de validação do executor imediato para instruções de outras classes. Exemplos:
- COP0 DMFC0/DMTC0: formatos definidos, mas não implementados; o classificador deliberadamente não os marca como RI, porém o despacho termina no executor imediato.
- COP1 LWC1/SWC1 ou formatos COP1: ainda não implementados; também caem no executor imediato.
- Outros opcodes fora do subconjunto imediato podem produzir erro semelhante, sem uma categoria explícita de capacidade.

Os testes atuais já documentam o caso de LWC1 como `IllegalArgumentException`, mas isso não é uma interface de resultado arquitetural clara.

### Correção proposta

Criar um resultado/erro tipado para **instrução válida, porém ainda não implementada**, separado de:
1. instrução executada;
2. codificação reservada que gera RI;
3. instrução válida não implementada;
4. encoding inválido cujo comportamento ainda precisa ser classificado pelo manual.

O stepper deve verificar capacidades de executores e terminar com esse resultado tipado, sem despachar por engano para o executor imediato. A falha não deve avançar PC, concluir delay slot nem alterar registradores/memória.

## 4. Lacunas adicionais a tratar

- SPECIAL: o decoder reconhece várias operações válidas; algumas são executadas, JR/JALR são tratadas pelo fluxo de controle e SYSCALL/BREAK geram exceções. As operações válidas restantes precisam de classificação explícita como implementadas ou não implementadas. Não inferir suporte apenas pela presença no enum.
- COP0: MFC0/MTC0 são executadas; DMFC0/DMTC0 são reconhecidas como transferências válidas mas ainda não executadas; operações CO/TLB/ERET e efeitos de privilégio não estão integrados ao executor.
- COP1/FPU: não há executor COP1 no conjunto ativo inspecionado.
- Controle em delay slot: transferência de controle dentro de delay slot ainda é rejeitada por uma verificação interna; não há tratamento arquitetural completo desse caso.
- Memória: o executor documenta limitação a RDRAM KSEG0/KSEG1, sem MMIO e sem tradução TLB. Exceções de endereço dependem do mapa/faults efetivamente modelados.
- HI/LO: divisão por zero e casos de overflow imprevisível são rejeitados preservando estado; é necessário decidir/documentar o comportamento de compatibilidade sem inventar valores.
- COP0: a leitura de Status é pré-condição para determinar vetor de exceção; se Status não estiver conhecido, a execução falha antes da atualização de exceção. Isso é intencionalmente conservador, mas precisa ser coberto nos testes de integração.

## 5. Matriz resumida

| Área | Estado no source set limpo | Próximo trabalho |
|---|---|---|
| GPR/HI/LO | Estado parcial com rastreio de conhecimento | Revisar invariantes e snapshots |
| Decoder | Campos e enum SPECIAL | Separar decode de capability |
| SPECIAL | Subconjunto executado | Inventariar todos os funct codes |
| Imediatas | Subconjunto implementado | Matriz de opcodes e testes |
| Loads/stores | Subconjunto RDRAM | TLB/MMIO e faults depois |
| COP0 | MFC0/MTC0 | DMFC0/DMTC0 e operações privilegiadas |
| COP1 | Sem executor ativo | Adicionar quando o alvo exigir |
| Delay slots | Caminho comum implementado | Tratar transferências em delay slot |
| Exceções | Subconjunto tipado | Cobertura arquitetural e TLB |

## 6. Plano de correção em ordem

1. Introduzir classificação tipada de capacidade da instrução sem alterar ainda o comportamento de instruções já implementadas.
2. Fazer o stepper rejeitar explicitamente instruções válidas não suportadas, sem usar o executor imediato como fallback.
3. Criar testes para COP0 DMFC0/DMTC0, COP1 LWC1/SWC1, SPECIAL válido não implementado e opcode reservado.
4. Verificar que PC, GPR, HI/LO, COP0 e delay-slot state permanecem inalterados em instrução não suportada.
5. Manter separados os testes de RI arquitetural e de capacidade ausente.
6. Executar toda a suíte limpa e validar CI antes de registrar a correção como concluída.

## 7. Limite desta auditoria

Esta é uma inspeção estática dos arquivos indicados. Nenhum código foi alterado nesta auditoria de CPU e nenhum teste novo foi executado. Não declarar a lacuna corrigida até que a implementação, os testes e o CI confirmem o resultado.

