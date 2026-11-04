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
- **Acessos desalinhados e merge:** LDL/LDR e LWL/LWR têm testes que percorrem todos os offsets; SWL/SWR também percorrem todos os offsets de palavra; SDL/SDR percorrem todos os offsets de doubleword.
- **COP0/TLB/LL-SC:** existem testes próprios para Count/Compare, ERET, TLBP/TLBR/TLBWI/TLBWR, exceções TLB e reserva LL/SC.

## Resultado da cobertura de merge stores

A inspeção do trecho completo confirmou testes de tabela para todos os offsets: `swlAndSwrStoreBytesCorrectlyAtEveryWordOffset` cobre SWL/SWR (0–3), e `sdlAndSdrPreserveBytesCorrectlyAtEveryDoublewordOffset` cobre SDL/SDR (0–7). Portanto, não há lacuna de cobertura de offsets identificada nesta área. A observação preliminar de cobertura incompleta foi corrigida após a leitura integral do bloco de testes.

## Limites conhecidos mantidos

- O cabeçalho de `R4300Cpu.kt` declara subconjunto incompleto, incluindo semântica completa de delay-slot exceptions, COP0/TLB e cache.
- COP1/FPU também é parcial: FCC0 apenas para branch/compare, subset de formatos/operações, sem implementação integral de exceções IEEE-754.
- Não inferir que a CPU esteja completa porque os testes existentes passam.
- Não modificar o decoder, nem alterar o comportamento arquitetural, sem teste de regressão que demonstre a falha.

## Segurança da alteração

Nenhum arquivo de produção foi modificado nesta auditoria. Nenhum ROM, dado protegido ou arquivo de jogo foi adicionado ao repositório. A branch `main` permaneceu intacta.
