# Auditoria da classificação de exceções COP0 — versão limpa

Data da consolidação: 2026-11-06  
Branch de trabalho: `dev/n64-hle-clean-reboot`  
Escopo: exclusivamente `app/src/clean/java`, `app/src/cleanTest/java` e `docs/`.

## 1. Objetivo

Consolidar as alterações feitas no classificador de instruções de exceção R4300/COP0 e separar três conceitos que não podem ser confundidos:

- formato de instrução reconhecido;
- codificação reservada que deve gerar Reserved Instruction (RI);
- codificação inválida/indicada como `φ` na tabela técnica, que não deve ser automaticamente tratada como RI.

Esta auditoria não declara COP0 completo nem comprova execução de jogo.

## 2. Alteração de produção confirmada

Arquivo: `app/src/clean/java/com/jsr/n64android/N64HleR4300ExceptionInstructionClassifier.kt`.

O refinamento substituiu a regra ampla de “função CO desconhecida gera RI” por uma lista explícita de funções CO reservadas identificadas na tabela VR4300:

- `0x0D`
- `0x0F`
- `0x10` (RFE)

As demais funções CO não são automaticamente classificadas como RI por este classificador. Isso inclui encodings `φ`, que precisam continuar distintos de instruções reservadas geradoras de RI.

Também permanecem reconhecidos como formatos de transferência COP0 os valores `rs`:

- `0x00` — MFC0
- `0x01` — DMFC0
- `0x04` — MTC0
- `0x05` — DMTC0

Reconhecer esses formatos não significa que todos estejam executáveis: o executor COP0 documentado anteriormente ainda implementa somente MFC0/MTC0.

## 3. Testes adicionados/ajustados

Arquivos:
- `app/src/cleanTest/java/com/jsr/n64android/N64HleR4300ExceptionInstructionClassifierTest.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64HleR4300InstructionStepperTest.kt`

Cobertura registrada nos commits:
- formatos de transferência COP0 válidos não são classificados como RI;
- funções CO `0x0D`, `0x0F` e `0x10` são classificadas como RI;
- encodings CO selecionados, incluindo exemplos `φ`, não são marcados como RI;
- RFE percorre o caminho integrado de exceção e verifica código Cause, EPC e vetor geral.

Correção estrutural de teste: o teste integrado de RFE foi movido para dentro da classe de testes do stepper. Também foi removida uma expectativa genérica antiga que contradizia a classificação específica de COP0.

## 4. Commits de referência

- Produção — classificação COP0 CO: [d8429d7](https://github.com/jovemaprendz55-byte/N64-Android/commit/d8429d7463d77142531e185968cc323e009b8795)
- Testes — RI versus não-RI: [64f2132](https://github.com/jovemaprendz55-byte/N64-Android/commit/64f2132095b5a2c247d7cd6608476ba2e4cf59e6)
- Teste integrado de entrada em RI: [3bdba4d](https://github.com/jovemaprendz55-byte/N64-Android/commit/3bdba4d255fe67299faf7e7460264e8c68e5d33f)
- Remoção de expectativa obsoleta: [222c0d0](https://github.com/jovemaprendz55-byte/N64-Android/commit/222c0d023b720659720aa69b4552a323e3c7408c)
- Correção do escopo da classe de teste: [c8d5807](https://github.com/jovemaprendz55-byte/N64-Android/commit/c8d5807224ea40b8cdf5f7bb0ab962c21b9082b4)

## 5. Validação e limites

Na consulta de status feita durante esta consolidação, o endpoint de status combinado não retornou verificações para os SHAs consultados. A consulta de execuções associadas ao commit consultado também não retornou workflow. Portanto:

- **Confirmado por inspeção de diff:** alteração do classificador e alterações dos testes acima.
- **Ainda não confirmado nesta consolidação:** execução verde da suíte limpa para o conjunto final desses commits.
- **Não comprovado:** execução de ROM no aparelho, boot de IPL3/PIF, compatibilidade de jogo ou emulação funcional.

Não interpretar ausência de status como sucesso. Antes de marcar a etapa como aprovada, localizar uma execução de CI que corresponda ao HEAD efetivo da branch e verificar seu resultado.

## 6. Limitações que continuam abertas

1. O classificador de RI não é um executor COP0 completo.
2. DMFC0/DMTC0 continuam formatos reconhecidos, mas ainda precisam de implementação ou resultado tipado de capacidade ausente.
3. TLB, ERET, WAIT, privilégio, CU0 e efeitos completos dos registradores COP0 permanecem fora do escopo implementado até prova em contrário.
4. O problema de despacho genérico no stepper descrito em [AUDITORIA_R4300_CLEAN_2026-11-06.md](AUDITORIA_R4300_CLEAN_2026-11-06.md) permanece uma tarefa separada; esta alteração de classificador não o resolve.
5. Não generalizar “função CO desconhecida” como sinônimo de RI. A classificação deve continuar baseada na tabela específica do VR4300.

## 7. Próxima sequência verificável

1. Confirmar o HEAD real da branch e localizar a execução de CI correspondente.
2. Executar/confirmar a suíte `cleanTest` e registrar os resultados completos.
3. Inspecionar o stepper atual e fechar o despacho de instruções válidas não implementadas sem fallback para o executor imediato.
4. Acrescentar testes de preservação do estado arquitetural para instruções válidas ainda não suportadas.
5. Atualizar este documento somente com resultados efetivamente observados.

## 8. Referência técnica

Manual NEC VR4300, tabela COP0/CO e legenda de códigos reservados/ inválidos:
https://n64dev.org/p/U10504EJ7V0UMJ1.pdf

---
Este documento é um registro técnico da versão limpa. Não usar implementação de outra versão do repositório como fonte de migração.
