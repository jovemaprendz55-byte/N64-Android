# Seção 158 — cobertura automatizada do BNE tomado com offset negativo

Data: 2026-11-05  
Branch: `dev/n64-hle-clean-reboot`

## Contexto

A captura física de oito instruções mostrou o opcode `0x1520FFFC` em PC `0xFFFFFFFF8000041C`, que decodifica como `BNE $t1, $zero, -4`. O PC apresentado imediatamente após esse branch é `0xFFFFFFFF80000420`, o endereço do delay slot. Esse valor é esperado antes da execução da instrução do delay slot.

Com o valor de GPR9 igual a `0x0000000000031078`, o branch deve ser tomado. Depois de executar uma instrução no delay slot, o PC deve mudar para `0xFFFFFFFF80000410`.

## Testes adicionados

- `N64HleR4300ControlTransferPlannerTest`: reproduz o opcode exato `0x1520FFFC`, no PC `0xFFFFFFFF8000041C`, com GPR9 não zero; verifica delay slot `0xFFFFFFFF80000420`, destino `0xFFFFFFFF80000410`, condição tomada e ausência de mutação antecipada do PC.
- `N64HleR4300ControlFlowSequencerTest`: verifica que o sequenciador primeiro instala o PC do delay slot, mantém a transferência pendente e só instala o destino negativo depois de `completeDelaySlot()`.

## Commits

- Planner: [cca1143b2986ff605828c974ab36b4e882a28651](https://github.com/jovemaprendz55-byte/N64-Android/commit/cca1143b2986ff605828c974ab36b4e882a28651)
- Sequencer: [d5ecb323fa2f3288f6bc1778991d89f86648b50e](https://github.com/jovemaprendz55-byte/N64-Android/commit/d5ecb323fa2f3288f6bc1778991d89f86648b50e)

## Limites e próximo passo

Estes testes cobrem o planejamento do BNE tomado e a sequência do delay slot. Eles não provam, sozinhos, que o registrador GPR9 no aparelho contém esse valor, porque a captura da UI não mostra o dump de registradores. Também não confirmam boot completo, RSP/OSTask, vídeo ou áudio.

A próxima validação é confirmar CI dos dois commits. Depois, retomar o teste físico de passo único: executar uma única instrução em `0xFFFFFFFF80000420`. Se GPR9 realmente for diferente de zero e a instrução do delay slot terminar normalmente, o PC esperado é `0xFFFFFFFF80000410`. Não habilitar execução contínua.
