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


## Atualização da auditoria após os commits de 09/11/2026

Esta seção substitui as afirmações anteriores deste documento que diziam que o coordenador ainda não estava integrado ou que a JNI de EXECUTE retornava sempre pelo safety gate. Essas afirmações ficaram desatualizadas após as alterações posteriores.

### Integração nativa confirmada no código atual

- `nativeInitialize`, `nativeStartCore`, testes/registro de Video Extension, carregamento de GFX/RSP e abertura/anexo da ROM adquirem leases `Operation::Other`.
- `nativeExecuteCore` adquire lease `Execute`, reserva a fase `ExecuteReserved`, valida handles sob `g_coreMutex`, libera o mutex antes de chamar `CoreDoCommand(M64CMD_EXECUTE)`, marca `Executing` e só libera a lease depois do retorno síncrono.
- `nativeStopCore` e o caminho STOP de `nativeShutdown` adquirem lease `Stop`; a chamada STOP ocorre sem manter `g_coreMutex`, enquanto a lease protege o tempo de vida dos handles.
- `nativeShutdown` fecha admissão, espera o handshake R4300 quando necessário, solicita STOP, espera a drenagem e só então adquire `g_coreMutex` para detach/ROM_CLOSE/PluginShutdown/dlclose/CoreShutdown.
- `nativeGetExecutionReadiness` não adquire lease, mas lê os handles/flags sob `g_coreMutex`; não chama funções do Core nem altera estado.
- Os testes de `CoreCallGate` e `CoreRuntimeCoordinator` cobrem drenagem, admissão STOP, timeout, reserva EXECUTE, retorno natural e waiters concorrentes. Eles ainda são testes de primitivas/coordenador, não integração real com o Core upstream, EGL ou callbacks dos plugins.

### Estado real de EXECUTE e acesso pela interface

- A JNI `nativeExecuteCore` contém agora uma chamada real a `CoreDoCommand(M64CMD_EXECUTE)`; não é correto dizer que a função nativa retorna sempre pelo safety gate.
- Em `MainActivity.kt`, o botão de execução continua desativado e o método `executeCoreOnGlThread()` existe, mas a busca no arquivo atual encontrou somente a declaração do método, sem call site. Portanto, a interface não oferece atualmente um caminho ativo para iniciar EXECUTE.
- A presença da implementação JNI não é autorização para habilitar o botão. Antes disso, o teste de STOP concorrente, os callbacks reais, o lifecycle Android e a segurança de EGL ainda precisam ser validados.

### Riscos Android ainda abertos

1. `onPause()` chama `nativeShutdown()` na thread da Activity, que pode esperar até 1500 ms pelo handshake e até 1500 ms pela drenagem; depois pode agendar novo shutdown na thread GL e aguardar até 3000 ms. Isso pode bloquear a UI por vários segundos.
2. Se a confirmação falhar, o código atual evita explicitamente `glView.onPause()` em `onPause()`, mas ainda chama `super.onPause()`. Isso não garante que a superfície EGL permaneça utilizável durante a transição do Android.
3. `onDestroy()` agenda shutdown na thread GL e espera até 3000 ms; independentemente de sucesso/timeout, termina chamando `surface?.onPause()`. Se EXECUTE ainda estiver ocupando a thread GL, o evento pode ficar enfileirado e o timeout não cancela EXECUTE.
4. Não há ainda um coordenador assíncrono Kotlin único que serialize parada manual, `onPause`, `onDestroy`, troca de tela e callbacks tardios, sem reter indevidamente a Activity.
5. A validação de STOP ainda precisa ser exercitada contra o Core real. O teste unitário de leases não prova que o Core upstream aceita STOP nas condições e no timing do handshake.

### Próxima etapa segura

Não alterar o botão nem iniciar ROM no loop. Primeiro, implementar e testar um coordenador Android de lifecycle que represente explicitamente: pedido de parada, retorno síncrono confirmado de EXECUTE, teardown GL com EGL atual e resultado de timeout/falha. O desenho deve tratar a impossibilidade de manter a Activity/superfície indefinidamente viva depois de `onDestroy()`; timeout deve preservar handles e nunca ser reportado como shutdown concluído. Em paralelo, adicionar testes instrumentados ou um harness de integração que valide STOP concorrente com o Core fixado.

**Estado de liberação:** botão permanece desativado; não há evidência no aparelho de `DoRspCycles`, tarefa gráfica HLE, `ProcessDList`, swap de frame de jogo ou imagem visível.
