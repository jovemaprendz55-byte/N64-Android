# Auditoria pré-implementação da máquina de estados nativa — 2026-11-09

## Revisão realizada

Branch: `main`  
Commit-base: `d32b05ba8f1fe4d97a900d0e0e794f89bb9423c6`  
Arquivo nativo: `app/src/main/cpp/native-lib.cpp`  
Blob revisado: `d960e1030d8e2ffe8d241ad3b02e49bbfaf4fe25`  
Arquivo Android: `app/src/main/java/com/jsr/n64android/MainActivity.kt`

Esta é uma auditoria do código-fonte atual. Não é uma implementação nem um teste no aparelho.

## Achados confirmados no JNI

1. `g_coreMutex` protege as operações de inicialização e carregamento.
2. `nativeInitialize`, `nativeStartCore`, `nativeLoadGfxPlugin`, `nativeLoadRspPlugin` e `nativeOpenRomAndAttachGfx` usam `g_coreMutex`.
3. `nativeExecuteCore` continua retornando antes de chamar `M64CMD_EXECUTE`. Portanto, `g_coreExecuting` não representa hoje uma execução real.
4. `nativeStopCore` mantém `g_coreMutex` durante a chamada a `CoreDoCommand(M64CMD_STOP)`. O retorno do STOP é assíncrono e não comprova que o loop de execução terminou.
5. `nativeShutdown` mantém `g_coreMutex` durante detach, fechamento da ROM, shutdown dos plugins e CoreShutdown.
6. A proteção de `nativeShutdown` consulta apenas `g_coreExecuting`; não há enumeração de estados STARTING/RUNNING/STOP_REQUESTED/STOPPED.
7. As funções de carregamento e abertura não consultam uma máquina de estados de execução. Antes de habilitar EXECUTE, todas as operações capazes de alterar Core, ROM, plugins ou handles precisam rejeitar STARTING, RUNNING e STOP_REQUESTED.
8. O fluxo de abertura da ROM também executa ROM_OPEN e attach dos plugins sob o mutex. Isso é aceitável no fluxo atual sem execução, mas precisa continuar mutuamente exclusivo com transições e cleanup.

## Achados confirmados no Android

- `executeCoreOnGlThread()` agenda a chamada na GL thread, mas não há caminho de UI ativo para execução; o botão permanece bloqueado.
- `onPause()` chama `glView.onPause()` imediatamente.
- `onDestroy()` agenda `nativeShutdown()` na GL thread e espera até 1500 ms. O timeout não confirma que o cleanup ocorreu.
- Se uma chamada síncrona EXECUTE ocupar a GL thread, tarefas enfileiradas nessa mesma thread não podem ser consideradas mecanismo de parada.

## Requisitos antes de editar o JNI

### Estado e sincronização

- Introduzir enum de estado explícito, protegido por `g_coreMutex`, com pelo menos IDLE, STARTING, RUNNING, STOP_REQUESTED, STOPPED e SHUTTING_DOWN.
- A transição para STARTING e a validação de Core/ROM/GFX/RSP devem ser atômicas sob o mutex.
- Capturar o ponteiro de `CoreDoCommand` e os handles necessários sob o mutex; liberar o mutex antes da chamada síncrona EXECUTE.
- Ao retornar de EXECUTE, adquirir o mutex e só então marcar STOPPED e sinalizar conclusão.
- STOP deve ser solicitado fora da GL thread, sem depender da fila de eventos da GLSurfaceView.
- O caso STOP solicitado durante STARTING precisa de tratamento explícito. Não presumir que um STOP anterior ao estado running será consumido pelo Core.
- Todas as operações de mutação de recursos devem recusar enquanto STARTING/RUNNING/STOP_REQUESTED.
- A chamada de STOP deve ser idempotente. A transição para STOPPED deve ocorrer uma única vez, somente após retorno real de EXECUTE.
- Evitar manter o mutex durante o loop EXECUTE. Avaliar com cuidado se o comando STOP pode ser chamado enquanto outra thread está dentro de CoreDoCommand(EXECUTE), conforme o contrato do Core fixado.

### Android lifecycle

- Criar consulta/callback de estado nativo para o coordenador Kotlin.
- Ao pausar, solicitar STOP e só pausar a GLSurfaceView depois da confirmação STOPPED.
- Ao destruir, não descarregar bibliotecas nem assumir sucesso por timeout. Se não houver confirmação, preservar handles e bibliotecas.
- Garantir que detach/shutdown gráfico só aconteçam com EGL current.
- Cobrir a troca de tela e a remoção/recriação da GLSurfaceView, não apenas onPause/onDestroy.

## Plano de implementação incremental

1. Adicionar enum e função interna de transição/consulta, mantendo EXECUTE bloqueado.
2. Aplicar guards a todas as funções JNI que inicializam, carregam, abrem, anexam, fecham ou descarregam recursos.
3. Implementar pedido STOP e sinalização de conclusão sem liberar recursos antecipadamente.
4. Implementar o coordenador Kotlin para pause/destroy, ainda sem habilitar EXECUTE.
5. Adicionar testes nativos de transição, STOP duplicado e corrida STARTING/STOP.
6. Revisar o contrato upstream do Core e fazer build.
7. Só depois planejar um teste controlado no aparelho.

## Estado de segurança

**EXECUTE continua bloqueado.** Esta auditoria não habilita emulação, não comprova primeiro frame e não deve ser interpretada como APK pronto para testar execução. O usuário já confirmou ROM aberta, GLideN64 e RSP-HLE anexados e InitiateGFX/InitiateRSP concluídos; ainda não confirmou DoRspCycles, tarefa gráfica HLE, ProcessDList ou apresentação de imagem do jogo.
