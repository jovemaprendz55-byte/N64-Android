# Auditoria detalhada da ordem de pausa e destruição — 2026-11-09

## Arquivos e revisão

- Branch: main
- Arquivo Kotlin: app/src/main/java/com/jsr/n64android/MainActivity.kt
- Arquivo JNI: app/src/main/cpp/native-lib.cpp
- Revisão realizada diretamente no código atual do branch main.

## Evidências Kotlin

### executeCoreOnGlThread (linhas 501–516)

A função agenda `nativeExecuteCore()` em `GLSurfaceView.queueEvent`. Se o Core executar um loop síncrono nessa thread, os eventos posteriores da fila não serão processados até o loop retornar.

No momento, a função JNI `nativeExecuteCore()` retorna pelo safety gate e não chama `M64CMD_EXECUTE`. Portanto, esse bloqueio de fila é um risco arquitetural futuro, não um comportamento de execução confirmado no aparelho.

### onPause (linhas 847–850)

O código chama imediatamente `glView?.onPause()` e depois `super.onPause()`. Não solicita STOP, não aguarda o retorno de EXECUTE e não confirma que o Core deixou de acessar EGL.

**Requisito:** quando houver execução real, onPause deve iniciar uma transição assíncrona: solicitar STOP pelo caminho independente da GL thread; aguardar confirmação de que a chamada síncrona EXECUTE retornou; só então pausar a GLSurfaceView. A Activity não deve bloquear a UI aguardando indefinidamente.

### onDestroy (linhas 852–895)

O código agenda `nativeShutdown()` na GL thread e aguarda 1500 ms. Em timeout, registra que as bibliotecas permanecem protegidas, mas prossegue com `surface?.onPause()` e `super.onDestroy()`.

Se EXECUTE estiver ocupando a GL thread, o evento de shutdown pode permanecer na fila. O timeout não é uma confirmação de cleanup. O shutdown nativo também exige contexto EGL atual quando o GFX está iniciado.

**Requisito:** separar solicitação de parada da liberação de recursos. Não descarregar Core/GFX/RSP até receber confirmação de retorno de EXECUTE e executar detach/shutdown com contexto EGL válido. Se a Activity for destruída antes disso, preservar as bibliotecas/handles; nunca interpretar timeout como sucesso.

## Evidências JNI

### nativeExecuteCore (linhas 869–884)

Retorna pelo bloqueio de segurança antes de chamar `M64CMD_EXECUTE`. A variável `g_coreExecuting` permanece falsa no caminho atual.

### nativeStopCore (linhas 889–912)

Verifica `g_coreExecuting`, localiza `CoreDoCommand` e envia `M64CMD_STOP`. O próprio texto de retorno reconhece que é necessário aguardar EXECUTE retornar. A função ainda mantém `g_coreMutex` durante a chamada STOP; a implementação futura deve validar a segurança dessa interação com o Core upstream fixado.

### nativeShutdown (linhas 916–1031)

Recusa cleanup quando `g_coreExecuting` é verdadeiro e exige EGL atual se o GFX estiver iniciado. Depois executa detach, ROM_CLOSE, PluginShutdown, dlclose e CoreShutdown. Essa ordem precisa permanecer inacessível enquanto EXECUTE ainda usa qualquer recurso.

## Máquina de estados e transições exigidas

- READY → STARTING: validar ROM, Core e plugins; impedir mudanças concorrentes.
- STARTING → RUNNING: registrar que EXECUTE começou.
- STARTING → STOP_REQUESTED: guardar pedido de parada pendente; não presumir que o Core já recebeu STOP.
- RUNNING → STOP_REQUESTED: enviar STOP pelo caminho autorizado, sem bloquear a GL thread.
- STOP_REQUESTED → STOPPED: somente quando a chamada síncrona EXECUTE retornar.
- STOPPED → SHUTTING_DOWN: somente com contexto EGL válido para a limpeza gráfica.
- SHUTTING_DOWN → IDLE: somente após detach, fechamento da ROM, shutdown dos plugins/Core e dlclose concluídos.

Todos os caminhos que inicializam, abrem ROM, carregam, anexam, destacam, fecham ou descarregam recursos devem rejeitar operações incompatíveis com STARTING, RUNNING ou STOP_REQUESTED.

## Plano de teste antes de liberar EXECUTE

1. STOP antes de EXECUTE.
2. STOP durante STARTING.
3. STOP durante RUNNING.
4. Dois pedidos STOP consecutivos.
5. STOP simultâneo ao retorno natural de EXECUTE.
6. onPause durante RUNNING.
7. onDestroy durante RUNNING.
8. Perda e recriação de EGL depois de STOPPED.
9. Falha de detach e tentativa posterior de cleanup.
10. Confirmar que nenhuma biblioteca é descarregada antes do retorno de EXECUTE.

## Estado atual

- EXECUTE continua bloqueado.
- Nenhum teste real de STOP durante execução foi feito, pois o caminho de execução está desabilitado.
- O usuário confirmou anteriormente ROM aberta, GLideN64 e RSP-HLE anexados e InitiateGFX/InitiateRSP concluídos.
- Ainda não há confirmação de DoRspCycles, tarefa gráfica HLE, ProcessDList, eglSwapBuffers bem-sucedido com frame de jogo ou imagem visível.

Esta auditoria documenta requisitos e riscos observados. Não altera o comportamento do APK e não declara o emulador funcional.
