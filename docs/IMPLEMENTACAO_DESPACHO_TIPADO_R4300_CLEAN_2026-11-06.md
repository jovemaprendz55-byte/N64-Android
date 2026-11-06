# Implementação inicial do despacho tipado R4300 — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
HEAD da implementação: `9089e664768ff78f6eac3106f6954b2afd831f75`

## Escopo

Somente a arquitetura limpa: `app/src/clean/java`, `app/src/cleanTest/java` e `docs/`. Nenhum código legado foi usado. A branch `main` não foi alterada.

## Alterações

- Adicionado `N64HleR4300StepResult.kt` com resultados tipados: Executed, ArchitecturalException, ReservedEncoding, ValidButNotImplemented e UnclassifiedEncoding.
- Adicionado `stepWithResult()` e `stepFromMemoryWithResult()` ao stepper, preservando as APIs antigas `step()` e `stepFromMemory()`.
- O fallback genérico para `immediateExecutor.execute()` foi removido. O stepper agora seleciona o executor por capacidade explícita.
- Adicionado `supports()` ao executor SPECIAL, baseado nas operações realmente implementadas.
- COP0 DMFC0/DMTC0, operações COP0 CO reconhecidas porém ainda sem executor, SPECIAL SYNC e LWC1/SWC1 são reportadas como válidas não implementadas.
- Codificações que o classificador já identifica como RI continuam entrando na exceção arquitetural; a nova API expõe isso como ReservedEncoding.
- Encodings sem classificação suficiente retornam UnclassifiedEncoding; não são presumidos como RI nem como instruções válidas.
- O tipo de exceção de instrução sem suporte herda de IllegalArgumentException para preservar compatibilidade com testes e chamadas existentes.

## Garantia pretendida e limites

- O despacho identifica falta de capacidade antes de chamar um executor inadequado.
- Para os casos de falta de executor cobertos aqui, o stepper não avança PC nem conclui um delay slot pendente.
- Esta mudança não implementa COP1/FPU, DMFC0/DMTC0, TLB, ERET, WAIT ou sincronização de barramento.
- Não declara CPU completa, boot completo, GoldenEye funcional ou execução no aparelho.

## Testes adicionados/atualizados

- Resultado válido não implementado para LWC1 sem avanço de PC.
- Resultado válido não implementado para DMFC0 sem mutação de GPR/PC.
- SPECIAL SYNC reconhecida como válida não implementada.
- Opcode ainda não classificado não é promovido a RI.
- Codificação REGIMM reservada continua sendo RI.
- Instrução válida não implementada no delay slot preserva PC e transferência pendente.

## Validação

- Commits de implementação: `cd311ae`, `027e3fb`, `5cb0ed1`, `4edf686`, `7f58c43`, `9089e66`.
- Ações do GitHub foram disparadas pelos pushes. No momento do registro, as execuções ainda estavam `in_progress`; não declarar CI aprovada até conferir o workflow no HEAD final.
- Nenhuma execução em aparelho foi realizada nesta etapa.

## Próximo passo

1. Conferir a CI do HEAD `9089e66` e corrigir qualquer falha.
2. Ampliar a classificação explícita para os formatos de memória válidos sem barramento configurado, sem adivinhar codificações reservadas.
3. Adicionar snapshot de estado arquitetural mais completo para GPR, HI/LO, COP0 e delay-slot.
4. Depois da aprovação, avançar para COP0/TLB e MMU em etapas pequenas e testáveis.
