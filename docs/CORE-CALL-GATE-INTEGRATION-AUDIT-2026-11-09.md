# Auditoria de integração do CoreCallGate — 2026-11-09

## Estado verificado

Esta auditoria foi feita sobre a branch `main`, após o protótipo isolado de `CoreCallGate`.

- `core_call_gate.h` existe e implementa leases para Execute, Stop e Other.
- `tests/native/core_call_gate_test.cpp` testa fechamento de admissão, entrada de STOP durante shutdown, drenagem das leases e reabertura após drenagem.
- O gate **ainda não está incluído nem utilizado por `native-lib.cpp`**.
- `nativeExecuteCore()` continua retornando antes de qualquer `M64CMD_EXECUTE`. A proteção não deve ser removida nesta etapa.

## Pontos confirmados no código atual

1. `nativeStopCore()` mantém `g_coreMutex` durante `dlsym` e a chamada síncrona a `CoreDoCommand(M64CMD_STOP)`.
2. `nativeShutdown()` mantém `g_coreMutex` durante detach dos plugins, fechamento da ROM, `PluginShutdown`, `CoreShutdown` e `dlclose`.
3. `nativeOpenRomAndAttachGfx()`, carregamento/inicialização dos plugins e outras operações também usam o mutex global. A integração precisa cobrir todas as operações que usam handles ou ponteiros de função, não somente Execute.
4. `M64CMD_STOP` é assíncrono: seu retorno não comprova que o loop síncrono de Execute terminou.
5. `MainActivity.onPause()` chama `glView?.onPause()` imediatamente; não solicita STOP nem aguarda o retorno de Execute.
6. `MainActivity.onDestroy()` agenda shutdown na thread GL e aguarda até 1500 ms. Timeout não comprova que o callback terminou; nesse caso, bibliotecas e recursos devem permanecer carregados.
7. O caminho de Execute permanece sem chamada alcançável para `M64CMD_EXECUTE`; portanto, não há confirmação de primeira tarefa RSP, processamento de lista gráfica ou frame real do jogo.

## Protocolo necessário antes de habilitar Execute

1. Fechar a admissão de novas operações Execute/Other.
2. Permitir uma lease Stop mesmo depois do fechamento.
3. Adquirir a lease Stop, proteger a vida útil do handle do Core e chamar `M64CMD_STOP` sem manter um mutex que impeça STOP de alcançar o Core.
4. Liberar a lease Stop e aguardar a drenagem de todas as leases, sem segurar o mutex necessário à operação STOP.
5. Somente após a drenagem, fazer detach, fechar ROM, encerrar plugins/Core e descarregar bibliotecas.
6. Se a drenagem ou o encerramento exceder o prazo, preservar handles e bibliotecas; nunca tratar timeout como encerramento concluído.
7. Garantir que operações de vídeo/destruição de recursos GL ocorram na thread com o contexto EGL atual.
8. Em `onPause()`, solicitar parada e aguardar o retorno efetivo de Execute antes de pausar a superfície GL.
9. Cobrir transições concorrentes de initialize, start, carregamento de plugins, abertura de ROM, registro de Video Extension, detach, shutdown e unload.

## Regra de segurança

Não integrar o gate parcialmente e não habilitar `M64CMD_EXECUTE` enquanto operações concorrentes ainda puderem usar handles durante `dlclose`, ou enquanto o ciclo Android pause/destroy não aguardar o retorno real de Execute.

## Próxima implementação recomendada

Projetar e testar um coordenador de runtime que combine admissão de leases e proteção da vida útil dos handles. Primeiro criar testes concorrentes independentes (Execute vs STOP vs shutdown, chamadas Other vs fechamento de admissão, timeout sem unload e falha de teardown); depois integrar cada ponto JNI sob esse protocolo. Manter a execução bloqueada até que a integração e os testes ARM64/Android estejam concluídos.
