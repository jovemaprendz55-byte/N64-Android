# Projeto do coordenador assíncrono de lifecycle Android — 2026-11-09

## Objetivo

Eliminar o bloqueio prolongado da thread principal e coordenar parada do Core, retorno síncrono de EXECUTE e teardown gráfico sem liberar recursos antes da hora. Este documento é um desenho de implementação; não altera nem libera a execução do emulador.

## Estado de partida confirmado em `main`

- `nativeExecuteCore()` contém a chamada síncrona a `CoreDoCommand(M64CMD_EXECUTE)`, mas não possui call site ativo na interface.
- O botão de execução continua desativado.
- `nativeStopCore()` solicita STOP fora da GL thread e usa lease `Stop`; o retorno do comando STOP não prova que EXECUTE retornou.
- `nativeShutdown()` fecha admissão, aguarda handshake/STOP e drain e, depois, exige EGL atual para detach e teardown.
- `onPause()` ainda chama `nativeShutdown()` diretamente na thread da Activity. Depois pode aguardar até 3 segundos por teardown na GL thread. Isso pode bloquear a UI por vários segundos.
- `onPause()` omite `GLSurfaceView.onPause()` quando shutdown não é confirmado, mas chama `super.onPause()`; não há garantia de que Android mantenha a janela/EGL.
- `onDestroy()` aguarda até 3 segundos por evento enfileirado na GL thread. Se falhar, omite a pausa explícita, mas `super.onDestroy()` ainda permite que Android destrua a janela.
- O seletor de ROM é exceção intencional: `romPickerActive` preserva o Core durante a pausa temporária do documento.

## Princípios que a implementação deve obedecer

1. Um único coordenador Kotlin serializa STOP manual, pausa, destruição, troca de tela e callbacks tardios.
2. Nenhuma espera longa de handshake/drain/STOP ocorre na thread principal.
3. STOP pode ser solicitado em worker enquanto EXECUTE ocupa a GL thread; não pode depender de `queueEvent`.
4. O worker não chama teardown gráfico. Só a GL thread, com EGL atual, pode chamar `nativeShutdown()` depois de EXECUTE ter retornado e os leases terem drenado.
5. Timeout significa `PENDING/UNCONFIRMED`, nunca `STOPPED` ou `SHUTDOWN_COMPLETE`.
6. Timeout preserva handles, plugins e bibliotecas. Não alterar flags Kotlin para descarregado sem resposta explícita de sucesso.
7. Não manter referência forte à Activity em worker ou callbacks depois de destruição. Usar estado/coordenador com escopo controlado e callback fraco ou token de geração.
8. Android controla a vida da janela. Não prometer manter EGL vivo depois de `onDestroy()`; se a destruição ocorrer antes da parada, preservar recursos nativos e reportar que o encerramento gráfico não foi concluído.
9. O seletor de ROM não deve disparar shutdown; a conclusão/cancelamento do seletor limpa o marcador de pausa temporária.
10. O botão EXECUTE permanece desativado até validação real do protocolo.

## Máquina de estados Kotlin proposta

- `IDLE`: nenhum pedido de parada pendente.
- `STOP_REQUESTED`: pedido enviado ao worker; Core ainda pode estar executando.
- `WAITING_FOR_EXECUTE_RETURN`: STOP aceito ou enviado; aguarda confirmação nativa de retorno.
- `STOPPED`: JNI confirmou que a chamada síncrona EXECUTE retornou e leases de execução foram liberados.
- `GL_TEARDOWN_QUEUED`: teardown agendado na GL thread; não é conclusão.
- `SHUTDOWN_COMPLETE`: `nativeShutdown()` confirmou descarregamento e teardown.
- `UNCONFIRMED`: timeout, falha, Activity destruída ou EGL indisponível; recursos permanecem protegidos.

Transições devem ser monotônicas por geração de operação. Um callback de geração antiga não pode sobrescrever estado de uma operação mais recente.

## Fluxo proposto

### Parada manual

1. UI solicita parada ao coordenador.
2. Coordenador muda para `STOP_REQUESTED` e executa `nativeStopCore()` num executor serial de lifecycle.
3. Coordenador não interpreta o texto de retorno "pedido enviado" como parada concluída.
4. Uma consulta/espera nativa dedicada deve confirmar retorno de EXECUTE e drain; preferir API nativa explícita de espera com timeout em vez de polling textual.
5. Quando STOPPED é confirmado e Activity/surface ainda estão válidas, agendar `nativeShutdown()` na GL thread apenas se a intenção for encerrar Core/plugins.
6. Só resposta positiva de shutdown permite remover/pausar a superfície e zerar flags locais.

### onPause

1. Se `romPickerActive`, preservar Core e seguir fluxo de pausa temporária.
2. Caso contrário, registrar intenção de pausa e solicitar STOP pelo worker.
3. Não bloquear a UI por segundos.
4. A superfície só pode ser explicitamente pausada após confirmação STOPPED e teardown gráfico quando necessário.
5. Se Android exigir completar `onPause()` antes disso, marcar operação como não confirmada; não fingir que EGL permanecerá disponível. A retomada deve revalidar EGL e reconciliar o Core antes de qualquer nova operação.

### onDestroy

1. Invalidar o token de callbacks da Activity imediatamente.
2. Solicitar STOP sem esperar na UI e sem capturar Activity fortemente.
3. Não enfileirar teardown GL e presumir que ele será executado após destruição.
4. Se STOPPED for confirmado enquanto surface/contexto ainda existem, teardown pode ser agendado pela referência de surface válida, com prazo limitado.
5. Se a janela for destruída primeiro, manter bibliotecas/handles protegidos e registrar estado `UNCONFIRMED`; não executar callbacks GL sem EGL.

## APIs nativas a considerar antes da alteração Kotlin

- `nativeRequestStop()`: idempotente; fecha a admissão de novas operações incompatíveis, envia STOP sob lease, retorna apenas resultado do pedido.
- `nativeAwaitExecuteReturned(timeoutMs)`: espera fora da UI; só retorna sucesso depois do retorno síncrono de EXECUTE e da liberação da lease Execute. Não faz detach, shutdown, dlclose ou GL.
- `nativeGetLifecycleSnapshot()`: consulta estruturada/estável de fase, execução ativa, STOP solicitado, leases ativos, ROM e plugins. Deve evitar strings ambíguas como protocolo.
- `nativeShutdown()`: continua reservado para teardown; só pode ser chamado na GL thread quando EGL é atual e a drenagem foi confirmada.

A implementação deve verificar se `CoreRuntimeCoordinator` atual já oferece as transições necessárias. Não criar uma segunda máquina de estados que possa divergir do estado nativo.

## Serialização e concorrência

- Um executor serial único deve ordenar pedidos de STOP/drain; não usar uma thread nova sem coordenação a cada toque.
- Pedidos repetidos de STOP devem compartilhar o mesmo resultado pendente.
- `onPause`, `onDestroy` e botão manual podem se sobrepor: a intenção mais restritiva (destruir) prevalece, sem duplicar STOP nem teardown.
- A espera por drain não pode manter `g_coreMutex` nem impedir que a chamada STOP obtenha lease.
- O lease STOP deve terminar antes de esperar drain completo; shutdown só começa após todos os leases liberados.
- Qualquer falha em STOP, drain, EGL ou teardown mantém handles e sinaliza estado não confirmado.

## Testes obrigatórios antes de liberar EXECUTE

### Nativos

- STOP antes do marcador R4300; verificar que não se perde o pedido.
- STOP durante EXECUTE; confirmar que STOP entra sem depender da GL thread.
- STOP duplicado concorrente; uma única chamada efetiva.
- retorno natural de EXECUTE concorrente com STOP.
- shutdown concorrente com STOP; nenhum `dlclose` enquanto lease STOP/Execute estiver ativo.
- timeout e erro do Core; handles continuam carregados.
- confirmação de drain não equivale a shutdown completo.
- chamadas mutáveis novas recusadas depois do fechamento de admissão.

### Android/Kotlin

- pausa durante execução, retomada antes/depois da confirmação.
- destruição durante execução e callback tardio.
- seletor de ROM abre/cancela/retorna sem encerrar Core.
- parada manual simultânea com onPause/onDestroy.
- surface recriada com novo EGL context.
- `queueEvent` não processado antes do prazo; nenhum falso sucesso.
- nenhuma atualização de UI após Activity destruída.
- executor encerrado sem manter Activity ou View retida.

### Aparelho

Somente depois dos testes acima e build aprovado, realizar teste controlado no Poco C85. Registrar separadamente:
- marcador R4300;
- chamada efetiva de STOP;
- retorno de `M64CMD_EXECUTE`;
- leases ativos zerados;
- teardown GFX/RSP/Core;
- contexto EGL atual;
- primeiro `DoRspCycles`;
- tarefa gráfica HLE;
- `ProcessDList`;
- `eglSwapBuffers` de frame de jogo;
- imagem real apresentada.

## Decisão atual

Não implementar mudanças de runtime neste commit de planejamento. O build do commit `be9335f6941dd6125cb52395530276fcfc667550` passou e publicou o artefato diagnóstico, mas isso não valida o ciclo assíncrono nem autoriza instalar como build de jogo. Manter `M64CMD_EXECUTE` bloqueado até o coordenador, testes e validação em aparelho estarem concluídos.
