# Auditoria complementar do lifecycle Android — 2026-11-09

## Escopo

Leitura do `MainActivity.kt` e de `native-lib.cpp` após a criação da primitiva `CoreCallGate`. Esta auditoria não habilita `M64CMD_EXECUTE` e não altera o runtime.

## Achados confirmados no Kotlin

1. `onPause()` chama `glView?.onPause()` imediatamente e depois `super.onPause()`. Não solicita `nativeStopCore()`, não espera o retorno de uma eventual execução síncrona e não confirma que o Core parou antes de suspender a superfície.
2. `onDestroy()` agenda `nativeShutdown()` com `surface.queueEvent` e espera no máximo 1500 ms. Se a thread GL estiver ocupada dentro de `M64CMD_EXECUTE`, o evento de shutdown pode ficar na fila atrás da execução; o timeout não cancela a execução nem prova que o shutdown ocorreu.
3. Se a superfície não estiver pronta, `onDestroy()` chama `nativeShutdown()` diretamente. A proteção EGL nativa pode recusar o teardown gráfico, mas isso não equivale a parar o Core nem a concluir o encerramento.
4. O botão "Parar emulação" chama `nativeStopCore()` diretamente no listener da UI. A futura integração deve manter STOP capaz de alcançar o Core mesmo enquanto a thread GL está ocupada em EXECUTE; a chamada não pode depender de um mutex mantido durante a execução síncrona.

## Achados confirmados no C++

1. `nativeExecuteCore()` continua retornando pelo safety gate antes de chamar `M64CMD_EXECUTE`; portanto, o cenário de execução concorrente ainda não foi exercitado no aparelho.
2. `nativeStopCore()` adquire `g_coreMutex` antes de localizar/chamar `CoreDoCommand(M64CMD_STOP)`.
3. `nativeShutdown()` mantém `g_coreMutex` durante detach, fechamento da ROM, shutdown dos plugins/Core e `dlclose`.
4. A primitiva `CoreCallGate` ainda não está incluída nem usada em `native-lib.cpp`.

## Requisitos antes de integrar e liberar EXECUTE

- Separar a proteção de handles/lifetime da serialização de operações de estado. Não manter `g_coreMutex` durante a chamada síncrona de EXECUTE.
- Fechar a admissão de novas operações antes do shutdown; permitir que o coordenador obtenha lease STOP, chame `M64CMD_STOP` e somente depois espere todos os leases, inclusive o próprio STOP.
- Capturar ponteiros e handles sob sincronização que garanta que nenhum `dlclose` ocorra enquanto a chamada estiver ativa.
- Fazer `onPause()` solicitar parada fora da thread GL ocupada, aguardar a confirmação real de retorno de EXECUTE e somente então pausar a superfície.
- Fazer `onDestroy()` distinguir "timeout aguardando" de "shutdown concluído". Timeout deve manter bibliotecas protegidas e não pode ser tratado como autorização para descarregar recursos.
- Testar os caminhos de pause, destroy, STOP concorrente, falha de STOP, timeout e falha parcial de teardown em testes nativos/Kotlin antes de liberar o botão de executar.

## Decisão

Não integrar o gate de forma superficial apenas envolvendo `nativeExecuteCore()`, `nativeStopCore()` e `nativeShutdown()`: isso deixaria chamadas Core/plugin e handles fora do protocolo e poderia preservar a corrida de lifetime. A emulação permanece bloqueada até a coordenação completa entre C++, thread GL e lifecycle Android.
