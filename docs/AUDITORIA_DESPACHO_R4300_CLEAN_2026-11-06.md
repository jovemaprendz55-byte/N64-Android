# Auditoria do despacho R4300 — matriz de capacidades da versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: somente source sets limpos e documentação.

## 1. Resultado principal

A inspeção confirma que o stepper ainda possui um fallback genérico inadequado:

```kotlin
else -> immediateExecutor.execute(instruction, state)
```

Esse ramo é alcançado por instruções que não pertencem ao executor imediato. O resultado é uma exceção de validação do executor errado, em vez de um resultado explícito do núcleo indicando “instrução válida ainda não implementada”, “codificação reservada” ou “capacidade ausente”.

O fallback não demonstra corrupção de estado: os executores inspecionados validam suporte antes de escrever os registradores. Porém, a API de falha não é arquitetural nem tipada, e a garantia deve ser reforçada por testes de snapshot do estado completo.

## 2. Matriz de despacho observada

| Classe | Capacidade encontrada | Limite atual |
|---|---|---|
| SPECIAL inteiro | O decoder reconhece várias funções; executor implementa subconjunto de shifts, aritmética, lógica, comparação, HI/LO e multiply/divide | Reconhecimento pelo decoder não significa execução. SYNC não está implementada no executor. |
| SPECIAL controle | JR/JALR passam pelo planner/sequencer de controle | Transferência de controle dentro de delay slot é rejeitada por precondição, sem exceção arquitetural completa. |
| SPECIAL exceções | SYSCALL/BREAK e traps são classificados pelo classificador | Tratamento limitado ao conjunto explícito do classificador. |
| Imediatas | ADDI, ADDIU, DADDI, DADDIU, ANDI, ORI, XORI, SLTI, SLTIU e LUI | Outros opcodes não devem ser enviados a este executor. |
| Memória | LB/LH/LW/LWL/LWR/LBU/LHU/LWU/LDL/LDR/LD e SB/SH/SW/SWL/SWR/SDL/SDR/SD | Depende do espaço de endereçamento guest/RDRAM configurado; não é MMU/TLB nem MMIO completo. |
| COP0 | MFC0 e MTC0 | DMFC0/DMTC0 são formatos reconhecidos pelo classificador, mas não são executados pelo executor atual. |
| COP1 | Nenhum executor COP1 ativo identificado nesta auditoria | Instruções como LWC1/SWC1 não podem ser enviadas ao executor imediato. |

## 3. Caminhos de falha concretos

### 3.1 COP1 LWC1

O teste atual `unsupportedInstructionDoesNotAdvancePc` usa a palavra `0xC4010000` (LWC1) e espera `IllegalArgumentException`. O fluxo chega ao fallback do executor imediato, que rejeita o opcode porque não está na lista de imediatas.

Conclusão: PC não avança porque a chamada falha antes do incremento, mas a falha não é um resultado tipado do núcleo.

### 3.2 COP0 DMFC0/DMTC0

O classificador não marca os formatos COP0 de transferência `rs=0x01` e `rs=0x05` como RI. O executor COP0 declara suporte apenas a `rs=0x00` e `rs=0x04`. Assim, DMFC0/DMTC0 caem no fallback imediato e recebem erro genérico de opcode não suportado.

Conclusão: não confundir “não é RI” com “está implementada”.

### 3.3 SPECIAL reconhecida mas sem execução

O decoder reconhece SYNC e várias outras funções SPECIAL. O executor SPECIAL tem um conjunto menor de operações efetivamente tratadas; uma operação reconhecida sem caso de execução termina em `UnsupportedOperationException`. Isso é melhor que executar silenciosamente, mas ainda não integra um resultado uniforme do stepper.

### 3.4 Delay slot

O teste `unsupportedDelaySlotInstructionDoesNotCompleteTransfer` registra que uma instrução não suportada no delay slot lança erro e não conclui a transferência pendente. Essa proteção deve ser preservada na futura API tipada.

## 4. Contrato recomendado para a correção

O stepper deve decidir o destino antes de chamar qualquer executor. Não deve usar um executor como fallback universal.

Resultado mínimo sugerido:

1. `Executed`: instrução executada; só então atualizar PC ou completar delay slot.
2. `ArchitecturalException`: exceção definida pela arquitetura, como RI, SYSCALL, BREAK, overflow ou fault de memória.
3. `ValidButNotImplemented`: formato/instrução reconhecido como válido, mas sem executor disponível.
4. `ReservedEncoding`: codificação que o classificador identifica explicitamente como reservada.
5. `UnclassifiedEncoding`: encoding ainda sem regra suficiente; não adivinhar que é RI.

Para `ValidButNotImplemented` e `UnclassifiedEncoding`, a chamada deve terminar sem alterar PC, GPR, HI/LO, COP0, memória ou metadados de delay slot. O chamador recebe um resultado explícito e pode mostrar diagnóstico. Não converter automaticamente falta de implementação em exceção arquitetural RI.

## 5. Testes necessários antes de aprovar

- COP1 LWC1 e SWC1: resultado válido não implementado; snapshot integral inalterado.
- COP0 DMFC0 e DMTC0: resultado válido não implementado; snapshot integral inalterado.
- SPECIAL SYNC: classificar separadamente e preservar estado se ainda não implementada.
- SPECIAL reservado: RI apenas quando a regra de codificação o determinar.
- Delay slot com instrução válida não implementada: PC, branch pendente e todos os registradores permanecem iguais.
- Opcode ainda não classificado: não cair no executor imediato e não ser rotulado RI sem evidência.
- Testar o caminho de instrução fornecida diretamente e o caminho de fetch da memória.

## 6. Evidências inspecionadas

- `N64HleR4300InstructionStepper.kt`: despacho e fallback.
- `N64HleR4300ImmediateExecutor.kt`: lista explícita de opcodes imediatos suportados.
- `N64HleR4300SpecialExecutor.kt`: operações SPECIAL realmente executadas e falha para operações sem caso.
- `N64HleR4300MemoryExecutor.kt`: conjuntos de opcodes de load/store.
- `N64HleR4300Cop0Executor.kt`: suporte apenas a MFC0/MTC0.
- `N64HleR4300InstructionDecoder.kt`: enum de operações reconhecidas; o próprio decoder documenta que reconhecimento não implica implementação.
- `N64HleR4300ExceptionInstructionClassifier.kt`: classificação separada de exceções arquiteturais.
- `N64HleR4300InstructionStepperTest.kt`: testes existentes para LWC1 e instrução não suportada no delay slot.

## 7. Status

- **Confirmado por inspeção estática:** fallback incorreto e caminhos acima.
- **Confirmado por teste-fonte existente:** LWC1 e instrução não suportada no delay slot atualmente esperam `IllegalArgumentException`.
- **Não executado nesta auditoria:** suíte Gradle/cleanTest.
- **Não alterado:** código de produção e testes.
- **Não comprovado:** emulação jogável ou execução de ROM no aparelho.

Próximo passo: implementar contrato de resultado tipado no stepper, com autorização para modificar código, e atualizar os testes para assertar preservação integral do estado.

---
Esta auditoria pertence exclusivamente à arquitetura limpa. Não utilizar código legado como base ou fonte de migração.
