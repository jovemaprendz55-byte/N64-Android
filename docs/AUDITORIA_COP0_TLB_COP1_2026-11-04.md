# Auditoria COP0, TLB e COP1 — 2026-11-04

## Escopo e método

Inspeção estática dirigida de `R4300Cpu.kt`, `R4300Tlb.kt` e dos testes `R4300CpuTest`, `R4300TlbTest`, `R4300TlbExceptionAccessTest`, `R4300Cop0CountTimerTest`, `R4300Cop1ArithmeticTest` e `R4300Cop1MemoryTest`, na branch `dev/n64-clean-start`. Não houve execução de ROM no aparelho nem comparação ciclo-a-ciclo com hardware real.

## COP0 e TLB — resultado

- Os registradores com regras especiais têm tratamento explícito: Index, Random, Wired, Context, EntryLo0/1, PageMask, EntryHi, Count, Compare, Cause, BadVAddr e PRId.
- O caminho TLB inclui TLBR, TLBWI, TLBWR e TLBP; há testes para probe hit/miss, bit P, índice de cinco bits, Random/Wired, globalidade dos dois EntryLo, entradas não inicializadas e round-trip dos registradores.
- `R4300Tlb` valida PFN de 20 bits, ASID, PageMask suportado, alinhamento do par virtual, páginas de tamanhos variáveis, V/D e globalidade. Há testes de tradução even/odd, grandes páginas, offsets, ASID, global, faults MISS/INVALID/MODIFIED e snapshot/restore atômico.
- Count/Compare tem testes de divisor pela metade, wrap unsigned e ACK isolado de IP7. ERET tem testes para EPC/EXL e ErrorEPC/ERL; LL reservation é limpa no retorno.
- Não foi confirmada duplicação que justificasse refatoração nem falha funcional demonstrável nesta inspeção. Nenhuma alteração de produção foi feita.

## COP1 — limites observados

- O código implementa transfers, BC1 com FCC0, operações S/D básicas, conversões, comparações FCC0 e loads/stores LWC1/LDC1/SWC1/SDC1. FR=0 usa pares even/odd; FR=1 usa registradores independentes.
- Os testes cobrem CU1, formatos S/D, pares FR=0, arredondamento de conversões, comparações unordered/ordered, FCR31, causas/flags/enable para alguns casos, e exceções de alinhamento em memória.
- O próprio código declara FPU parcial. `classifyBasicFpuExceptions` cobre apenas um subconjunto de invalid/divide-by-zero/overflow; não implementa o conjunto completo de exceções IEEE-754, incluindo detecção completa de underflow e inexact.
- As operações aritméticas usam operações `Float`/`Double` da JVM; não foi demonstrado que o modo RM de FCR31 controle o arredondamento de ADD/SUB/MUL/DIV/SQRT. Esse é um limite a validar, não uma correção aplicada nesta auditoria.
- Somente FCC0 é suportado em compare/branch; outros condition codes são explicitamente rejeitados. Não é uma FPU completa do VR4300.

## Próximos passos seguros

1. Acrescentar testes de conformidade COP1 para arredondamento RM em operações aritméticas, underflow/inexact, NaN/sinal de zero e comparações signaling/non-signaling, antes de alterar implementação.
2. Continuar semântica COP0 privilegiada e exceções de modo, especialmente Status/CU0/CU1/EXL/ERL e instruções CACHE, com testes isolados.
3. Não apresentar a FPU como completa nem inferir compatibilidade integral do R4300 a partir dos testes atuais.

## Segurança e estado

Nenhum código de produção foi alterado nesta auditoria. Nenhuma ROM foi adicionada ao repositório. A branch `main` não foi modificada. CI verde valida somente build e testes automatizados; não prova execução de GoldenEye no Android 16.
