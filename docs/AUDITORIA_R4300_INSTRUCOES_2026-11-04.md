# Auditoria dirigida do decoder R4300 — 2026-11-04

## Escopo

Revisão estática do decoder em `app/src/main/java/com/jsr/n64android/R4300Cpu.kt` e da cobertura em `app/src/test/java/com/jsr/n64android/R4300CpuTest.kt`, na branch `dev/n64-clean-start`.

Esta revisão não executou ROM no aparelho e não pretende certificar compatibilidade integral com MIPS III/VR4300. O arquivo de teste contém 243 declarações `@Test` na versão inspecionada.

## Resultado

**Nenhuma duplicação de lógica ou falha funcional foi confirmada nesta passada que justificasse alterar o decoder.** A CPU foi mantida sem alterações. A CI dos commits de código anteriores concluiu com sucesso nos runs 41800334365 e 41800415861 (build, testes unitários e upload do APK).

### Grupos revisados

- **Aritmética 32/64 bits:** ADD/ADDU, SUB/SUBU, DADD/DADDU, DSUB/DSUBU, DADDI/DADDIU, comparações e operações HI/LO têm testes de resultados, extensão e overflow.
- **Multiplicação/divisão:** operações signed/unsigned de palavra e doubleword têm testes de HI/LO e casos de bit alto.
- **Desvios:** BEQ/BNE/BLEZ/BGTZ, REGIMM e branch-likely, além de J/JAL/JR/JALR, têm cobertura de delay slot, alvo e anulação de delay slot.
- **Exceções em fluxo de controle:** há testes de RI, overflow, TLB, bus error e coprocessor unusable em delay slot, com verificação de EPC/BD.
- **Acessos desalinhados e merge:** LDL/LDR percorrem todos os offsets de doubleword; LWL/LWR também têm cobertura de offsets. SWL/SWR/SDL/SDR possuem testes de casos específicos.
- **COP0/TLB/LL-SC:** existem testes próprios para Count/Compare, ERET, TLBP/TLBR/TLBWI/TLBWR, exceções TLB e reserva LL/SC.

## Oportunidade de cobertura — sem defeito confirmado

Os testes de store-merge (`SWL`, `SWR`, `SDL`, `SDR`) não demonstram, pelos casos nomeados e inspecionados, uma varredura explícita de todos os offsets como ocorre com `LDL/LDR`.

Próximo passo recomendado: adicionar testes de tabela para cada offset de 0 a 3 (palavra) e 0 a 7 (doubleword), verificando os bytes escritos e preservados em memória. A expectativa deve ser derivada da ordem big-endian do R4300 e testada sem mudar a implementação. Só corrigir o decoder se um desses testes reproduzir uma divergência.

## Limites conhecidos mantidos

- O cabeçalho de `R4300Cpu.kt` declara subconjunto incompleto, incluindo semântica completa de delay-slot exceptions, COP0/TLB e cache.
- COP1/FPU também é parcial: FCC0 apenas para branch/compare, subset de formatos/operações, sem implementação integral de exceções IEEE-754.
- Não inferir que a CPU esteja completa porque os testes existentes passam.
- Não modificar o decoder, nem alterar o comportamento arquitetural, sem teste de regressão que demonstre a falha.

## Segurança da alteração

Nenhum arquivo de produção foi modificado nesta auditoria. Nenhum ROM, dado protegido ou arquivo de jogo foi adicionado ao repositório. A branch `main` permaneceu intacta.
