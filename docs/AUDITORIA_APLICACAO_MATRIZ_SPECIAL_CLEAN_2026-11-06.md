# Auditoria de aplicação da matriz SPECIAL — versão limpa

Data: 2026-11-06
Branch: `dev/n64-hle-clean-reboot`

## Arquivos inspecionados
- `N64HleR4300InstructionDecoder.kt`
- `N64HleR4300SpecialExecutor.kt`
- `N64HleR4300ExceptionInstructionClassifier.kt`
- `N64HleR4300InstructionStepper.kt`
- `N64HleR4300ControlTransferPlanner.kt`
- `N64HleR4300SpecialExecutorTest.kt`
- `N64HleR4300InstructionDecoderTest.kt`

## Achado principal
Não existe validação estrutural dos campos SPECIAL. O decoder extrai campos e resolve `specialOperation` somente pelo `functionCode`. O executor verifica opcode SPECIAL e operação conhecida, mas não verifica os campos que devem ser zero.

Exemplos de encodings negativos a testar: SLL com `rs != 0`; SLLV com `shiftAmount != 0`; ADDU com `shiftAmount != 0`; MFHI com `rs != 0` ou `rt != 0`; MTHI com `rt`, `rd` ou `shiftAmount` não zero; MULT com `rd` ou `shiftAmount` não zero; JR com `rt`, `rd` ou `shiftAmount` não zero; JALR com `rt` ou `shiftAmount` não zero.

## Três caminhos afetados
1. SPECIAL comum: `executeNonControl()` encaminha diretamente ao executor.
2. JR/JALR: são desviados ao `controlPlanner.plan()` antes do executor SPECIAL; validar somente dentro do executor não cobre esses saltos.
3. SYSCALL/BREAK: o classificador reconhece a operação pelo function code antes de verificar a estrutura completa. O campo code permitido não deve ser confundido com bits reservados.

## Ordem recomendada
1. Decode.
2. Validação estrutural.
3. Encoding reservado: RI, preservando contexto de delay slot.
4. Classificação de SYSCALL/BREAK/traps.
5. Despacho para controle, COP0, SPECIAL, imediato ou memória.
6. Atualização do PC/conclusão do delay slot após sucesso.

Legalidade do encoding e capacidade de execução são coisas distintas: operação válida ainda não implementada não deve virar RI.

## Lacuna nos testes
Os testes de decoder cobrem extração e mapeamento de function code, não legalidade. Os testes SPECIAL cobrem resultados e algumas falhas sem mutação, mas não variam sistematicamente campos reservados. Faltam testes específicos para JR/JALR, SYSCALL/BREAK e RI em delay slot.

## Plano de testes
- Um encoding válido por operação reconhecida.
- Alterar individualmente cada campo restrito.
- SYSCALL/BREAK e traps com code não zero continuam válidos.
- SYNC canônico e variações nos bits 25..6.
- JR/JALR inválidos causam RI antes de planejar salto ou escrever link.
- RI em delay slot atualiza BD/EPC e cancela transferência.
- Snapshot de GPR, HI/LO, COP0, PC e flags.

## Limites
Inspeção estática e leitura de testes-fonte. Nenhum código de produção/teste foi alterado; Gradle, CI e aparelho não foram executados. Não declarar conformidade completa do R4300.

Documento exclusivo da versão limpa. Não reutilizar implementação da versão antiga.
