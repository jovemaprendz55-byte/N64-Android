# Teste isolado do protocolo de chamadas do Core — 2026-11-09

## O que foi adicionado

- `app/src/main/cpp/core_call_gate.h`: primitiva C++17 independente de Android para leases RAII de chamadas Core.
- `tests/native/core_call_gate_test.cpp`: teste concorrente executável no runner Linux.
- O workflow Android compila e executa esse teste com `-Wall -Wextra -Werror -pthread` antes do build ARM64.

## Contrato testado

1. Uma chamada EXECUTE mantém um lease até a chamada síncrona realmente retornar.
2. Ao iniciar o drain de shutdown, novas chamadas EXECUTE e OTHER são recusadas.
3. STOP continua admitido durante o drain para que uma thread externa possa interromper o loop síncrono.
4. O drain não termina enquanto existir qualquer lease ativo.
5. Depois que o drain termina, até STOP é recusado.
6. Um teardown que falhou pode reabrir a admissão somente depois do drain completo e sem leases.
7. O lease é move-only e libera a reserva exatamente uma vez.

## Limites importantes

Este é um teste da primitiva de sincronização, não prova que a integração nativa esteja pronta. O header ainda não foi conectado a `native-lib.cpp`; nenhuma chamada Core real foi alterada e `M64CMD_EXECUTE` continua bloqueado.

Antes da integração, a implementação ainda precisa resolver a ordem de locks com `g_coreMutex`, garantir que STOP possa adquirir o lease sem ficar bloqueado pelo mutex mantido durante o drain, proteger todos os ponteiros/handles até o último lease sair e coordenar o EGL/lifecycle Kotlin. Não usar o teste isolado como autorização para habilitar a emulação.
