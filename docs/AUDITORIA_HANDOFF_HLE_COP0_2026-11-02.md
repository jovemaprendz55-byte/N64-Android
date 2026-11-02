# Auditoria dos parâmetros de handoff HLE e COP0 — 2026-11-02

Branch: `dev/n64-clean-start`
Escopo: inspeção estática de `N64HleBoot`, `R4300Cpu` e `N64HleBootTest`. Sem alteração funcional, sem APK e sem teste em aparelho.

## 1. Parâmetros de baixa memória escritos pelo HLE

O código define base `0x300` e grava:
- `0x300`: TV type;
- `0x304`: ROM type;
- `0x308`: ROM base;
- `0x30C`: reset type;
- `0x314`: IPL2 version;
- `0x318`: RDRAM size.

**Observação objetiva:** o endereço `0x310` não tem constante nem escrita no handoff atual. O código detecta um CIC seed e coloca o valor em `$s6`, mas não escreve nenhum campo de baixa memória entre reset type e IPL2 version.

Não preencher `0x310` com o seed por suposição. Primeiro confirmar a semântica exata do campo e a codificação esperada pelo IPL3/libultra para cada CIC suportado. Até lá, registrar como lacuna de compatibilidade do HLE.

## 2. COP0 após o reset usado pelo HLE

`N64HleBoot.prepare()` chama `cpu.reset()` e depois habilita `tlbEnabled`.

`R4300Cpu.reset()`:
- zera todos os 32 registradores COP0;
- limpa a TLB;
- desabilita a TLB inicialmente;
- volta PC ao reset vector.

O HLE então habilita a TLB, mas não inicializa explicitamente Status, Cause, Wired, Random, EntryHi ou demais COP0. Portanto, imediatamente após o handoff atual, Status é zero e BEV (bit 22) está limpo.

O caminho de exceção TLB escolhe:
- TLB MISS fora de EXL: `0x80000000` quando BEV=0; `0xBFC00200` quando BEV=1;
- demais faults ou faults aninhados: `0x80000180` quando BEV=0; `0xBFC00380` quando BEV=1.

A escolha está explícita em `enterTlbException()`. O código também atualiza BadVAddr, Context, EntryHi, Cause e Status.EXL.

**Não é possível concluir apenas pela inspeção se Status=0 e BEV=0 são o estado correto para o ponto em que o HLE entrega o controle à ROM.** Isso deve ser comparado com o estado real produzido pelo IPL3 para o CIC/ROM alvo. Não mudar BEV ou Status para outro valor sem referência.

## 3. Cobertura atual dos testes

`N64HleBootTest.preparesFirstMegabyteAndBootParametersWithoutExecutingIpl3OrUsingPifRom()` verifica:
- seed CIC detectado;
- cópia de 1 MiB e endereço de entrada;
- registradores GPR usados pelo HLE;
- TLB habilitada;
- parâmetros em `0x300`, `0x304`, `0x308`, `0x30C` e `0x318`;
- primeira instrução buscada via KSEG0.

O teste não verifica:
- valor/semântica de `0x310`;
- estado COP0 imediatamente após handoff;
- vetor de refill escolhido para BEV=0/1;
- comparação de estado com um handoff real de IPL3.

Os testes de `R4300Tlb` cobrem tradução de páginas, ASID/global, páginas inválidas, modified, PageMask e alinhamento, mas não substituem a validação do estado de boot da CPU.

## 4. Próximos passos sem adivinhação

1. Obter uma referência confiável para o significado e codificação do campo `0x310` e para os valores COP0 que o IPL3 deixa no handoff.
2. Se possível, comparar com um dump de boot real da mesma família CIC e ROM/região.
3. Criar testes unitários para os valores confirmados e para a seleção dos vetores BEV/refill.
4. Só então implementar os campos/registradores comprovados.
5. Reexecutar testes e CI; depois gerar APK e testar no aparelho com um log novo.

## 5. Estado

- Lacuna `0x310` identificada no conjunto de parâmetros escritos pelo HLE.
- Estado COP0 zero após reset e seleção de vetor TLB dependente de BEV confirmados no código.
- Correção ainda não aplicada porque a codificação/estado esperado não foi validada.
- Nenhum Kotlin alterado; nenhum APK gerado.
- `main` não foi alterada.
