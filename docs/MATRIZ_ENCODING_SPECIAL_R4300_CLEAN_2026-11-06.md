# Matriz normativa inicial de encodings SPECIAL — R4300 limpo

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: somente arquitetura limpa.

## 1. Fonte normativa

Referência principal: NEC, *VR4300, VR4305, VR4310 64-Bit Microprocessor User's Manual*, documento U10504EJ7V0UMJ1, 7ª edição, agosto de 2000. A tabela abaixo foi construída a partir dos diagramas de formato do capítulo 16, conferidos com os resumos do capítulo 3.

- Manual completo: https://n64dev.org/p/U10504EJ7V0UMJ1.pdf
- Formatos e resumo de instruções: capítulo 3, especialmente tabelas de shifts, computational e special instructions.
- Formatos detalhados: capítulo 16, entradas individuais de cada instrução.

A matriz descreve restrições de campos do encoding, não todos os requisitos de privilégio, estado de coprocessador, hazards ou timing.

## 2. Convenção dos campos

Encoding SPECIAL de 32 bits:

`opcode[31:26] | rs[25:21] | rt[20:16] | rd[15:11] | sa[10:6] | funct[5:0]`

Para todas as linhas desta matriz, `opcode = 0` e `funct` identifica a operação.

- `0`: campo deve ser zero.
- `operand`: campo utilizado como registrador/valor.
- `code`: campo de código definido pela instrução; não deve ser zerado.
- `sa`: shift amount.

## 3. Matriz de campos

| Família / operações | rs | rt | rd | sa | bits 15..6 / code |
|---|---:|---:|---:|---:|---|
| SLL, SRL, SRA | 0 | operand | operand | operand | rs=0 |
| DSLL, DSRL, DSRA, DSLL32, DSRL32, DSRA32 | 0 | operand | operand | operand | rs=0 |
| SLLV, SRLV, SRAV | operand | operand | operand | 0 | sa=0 |
| ADD, ADDU, SUB, SUBU, AND, OR, XOR, NOR, SLT, SLTU, DADD, DADDU, DSUB, DSUBU | operand | operand | operand | 0 | sa=0 |
| JR | operand | 0 | 0 | 0 | rt/rd/sa=0 |
| JALR | operand | 0 | operand | 0 | rt/sa=0 |
| MFHI, MFLO | 0 | 0 | operand | 0 | rs/rt/sa=0 |
| MTHI, MTLO | operand | 0 | 0 | 0 | rt/rd/sa=0 |
| MULT, MULTU, DIV, DIVU, DMULT, DMULTU, DDIV, DDIVU | operand | operand | 0 | 0 | rd/sa=0 |
| TGE, TGEU, TLT, TLTU, TEQ, TNE | operand | operand | não aplicável | não aplicável | code[15:6] livre |
| SYSCALL, BREAK | parte do campo code | parte do campo code | parte do campo code | parte do campo code | code[25:6] livre |
| SYNC | 0 | 0 | 0 | 0 | todos os bits 25..6 zero |

### Observações importantes

1. SYSCALL e BREAK carregam um campo code de 20 bits. Não rejeitar valores não zero nesses bits.
2. As instruções de trap SPECIAL carregam code de 10 bits em bits 15..6. Esse campo não é um conjunto de bits reservados.
3. SYNC tem formato mais restrito: todos os bits 25..6 são zero. O manual descreve SYNC como NOP no VR4300.
4. JALR usa rs e rd; rt e sa são zero.
5. JR usa rs; rt, rd e sa são zero.
6. Shifts variáveis usam rs, rt e rd, com sa zero.
7. Shifts imediatos usam rt, rd e sa, com rs zero.
8. Operações aritméticas/lógicas de três registradores usam rs, rt e rd, com sa zero.
9. MFHI/MFLO e MTHI/MTLO têm formatos diferentes; não aplicar uma máscara genérica para toda a família HI/LO.
10. As operações DSLL/DSRL/DSRA e variantes DSLL32/DSRL32/DSRA32 têm também restrições de modo de operação: o manual especifica RI em modo User/Supervisor de 32 bits. Isso é uma verificação de privilégio/modo, não uma regra de campo zero.

## 4. Cobertura em relação ao decoder limpo

O enum `N64HleR4300SpecialOperation` atualmente reconhece:
- shifts imediatos e variáveis de 32 bits;
- JR/JALR, SYSCALL, BREAK e SYNC;
- transferências HI/LO;
- multiply/divide 32 e 64 bits;
- aritmética, lógica, comparação e traps;
- shifts doubleword imediatos.

Não aparecem no enum atual DSLLV, DSRLV e DSRAV, embora façam parte do conjunto VR4300. Não os incluir no validador atual como operações reconhecidas sem primeiro ampliar explicitamente o decoder e testes.

## 5. Decisões de desenho para o validador

O validador deve ser puro e avaliar apenas o encoding bruto e o modo arquitetural quando este for fornecido explicitamente.

Resultado recomendado:
- `LegalEncoding(operation)`
- `ReservedEncoding(reason)`
- `UnknownOperation(functionCode)`
- `RequiresPrivilegeOrModeCheck(operation)` (ou resultado legal acompanhado de requisito de modo, se a API preferir separar fases).

Não confundir:
- encoding com campo reservado inválido → RI;
- encoding válido sem executor → válido não implementado;
- encoding válido que exige modo não permitido → RI por condição de modo;
- código de trap/SYSCALL/BREAK não zero → parâmetro, não RI por si só.

## 6. Testes parametrizados derivados da matriz

Para cada linha:
1. Construir ao menos uma palavra válida de referência.
2. Alterar individualmente cada campo que deve ser zero, mantendo os demais iguais.
3. Confirmar que o validador marca o encoding alterado como reservado.
4. Confirmar que valores arbitrários de campos `code` permitidos continuam válidos em SYSCALL, BREAK e traps.
5. Confirmar que o validador não lê GPRs, HI/LO ou COP0 e não altera estado.
6. Cobrir separadamente os requisitos de modo das operações doubleword.

## 7. Limitações e status

- Esta é uma matriz inicial para os SPECIAL listados no decoder limpo, não uma tabela de todas as instruções MIPS III.
- O manual distingue encoding, modo de operação, exceções e restrições temporais; a matriz não substitui esses capítulos.
- A auditoria de código anterior permanece válida: o executor SPECIAL ainda não valida estes campos.
- Não houve alteração de produção/testes e não houve execução de Gradle ou aparelho nesta etapa.

---
Documento exclusivo da versão limpa. Não utilizar código da versão antiga.
