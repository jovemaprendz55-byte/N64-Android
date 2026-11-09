# Plano de coordenacao serial do lifecycle Android — 2026-11-09

## Estado de entrada

- Branch: `main`.
- Commit de entrada: `e24543395ef21d5797643e28cae420698488ffda`.
- Build do commit: sucesso no Actions run [42676457480](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42676457480).
- Artefato de diagnostico: `n64-android-core-isolated-diagnostic`, ID `13701160836`, 3.141.640 bytes, nao expirado na verificacao.
- A correcao `activityDestroyed = true` esta em linha executavel no `onDestroy()`.
- Nenhum teste no aparelho foi inferido a partir do CI.

## Problema confirmado no Kotlin atual

1. O botao manual STOP usa worker thread e aguarda `nativeAwaitExecuteReturned(3000)`, mas continua chamando metodos JNI de instancia da Activity.
2. `onPause()` ainda chama `nativeShutdown()` diretamente na thread da Activity. Depois, `shutdownCoreBeforeSurfaceChange()` pode enfileirar outro shutdown na GL thread e aguardar ate 3 segundos. A UI pode ficar bloqueada por varios segundos.
3. `onDestroy()` aguarda ate 3 segundos por um shutdown enfileirado na GL thread. Se EXECUTE ocupar essa thread, o evento pode ficar preso atras dele.
4. O callback tardio do botao STOP verifica `activityDestroyed`, mas isso nao serializa STOP manual, pausa, destruicao, troca de tela e troca de ROM.
5. `onDestroy()` so chama explicitamente `surface.onPause()` quando considera o shutdown concluido; se nao concluir, Android ainda pode destruir a janela/superficie. Omitir a chamada explicita nao preserva EGL garantidamente.

## Protocolo exigido

Um coordenador unico deve ser dono do estado da operacao, e nao cada callback da Activity. A sequencia logica e:

`Idle -> StopRequested -> ExecuteReturned -> GlTeardownQueued -> ShutdownComplete`

Saidas de falha devem ser explicitas e terminais para aquela tentativa:

- `StopRejected`: STOP nao foi confirmado; nao esperar retorno como se tivesse sido enviado.
- `ExecuteReturnTimeout`: nao descarregar plugins/Core nem declarar parada.
- `SurfaceUnavailable`: nao executar teardown grafico fora da GL thread com EGL atual.
- `GlTeardownTimeout`: nao declarar shutdown concluido nem limpar os handles.
- `ActivityDestroyed`: suprimir somente callbacks de UI; nao cancelar a protecao de lifetime nativa.

## Regras de concorrencia

- Apenas um pedido de parada/encerramento pode estar ativo por vez. Pedidos repetidos devem aderir ao mesmo resultado, nao emitir STOP duplicado.
- A parada manual e os eventos de lifecycle devem solicitar a mesma operacao serial, com um identificador monotonicamente crescente para rejeitar callbacks antigos.
- O worker pode chamar STOP e aguardar o retorno real de EXECUTE. O worker nunca chama `nativeShutdown()`, `PluginShutdown`, detach, `dlclose` ou qualquer teardown que dependa de EGL.
- O teardown grafico continua exclusivo da GL thread, somente depois de EXECUTE retornar e enquanto o contexto EGL estiver atual.
- A referencia a Activity e a GLSurfaceView deve ser fraca ou destacavel; um worker nao pode manter uma Activity destruida viva.
- Como os metodos JNI atuais sao metodos de instancia de `MainActivity`, antes de criar um worker independente e necessario mover a ponte JNI para um proprietario de lifetime estavel (por exemplo, uma ponte nativa/application-scoped) ou provar que a referencia fraca e suficiente. A mudanca deve incluir revisao dos nomes/assinaturas JNI e testes de carga.
- Se a superficie desaparecer antes do teardown, manter os handles e registrar estado nao confirmado. Nao tentar resolver isso chamando teardown grafico na thread errada.
- A Activity nao deve bloquear a thread principal esperando STOP, retorno de EXECUTE ou teardown.

## Plano incremental de implementacao

1. Criar uma classe Kotlin pura para coordenar estados, identificadores de operacao, adesao a pedidos repetidos e resultados de timeout. Sem chamadas Android/JNI dentro da classe.
2. Adicionar testes JVM para: STOP repetido; pausa concorrente com STOP manual; destruicao concorrente; timeout; retorno natural de EXECUTE; seletor de ROM; callback de geracao antiga.
3. Introduzir uma interface pequena para a ponte nativa e testar um fake deterministico antes de conectar JNI real.
4. Resolver o lifetime da ponte JNI sem Activity e confirmar que STOP ainda pode chamar o Core sem bloquear na thread GL.
5. Integrar a conclusao do worker com um evento na GL thread. A GL thread deve revalidar geracao, estado e EGL antes do teardown.
6. Somente depois revisar `onPause()`, `onDestroy()`, seletor de ROM e troca de tela para que todos solicitem o mesmo coordenador sem bloquear a UI.
7. Executar CI, revisar diff e testar no Poco C85 com logs de cada transicao. Se a superficie for destruida antes da confirmacao, o resultado esperado e handles preservados, nao falso sucesso.

## Testes de integracao obrigatorios antes de liberar EXECUTE

- STOP concorrente com `M64CMD_EXECUTE` real no Core fixado.
- STOP repetido e STOP depois do retorno natural.
- `onPause()` durante EXECUTE, com e sem retorno dentro do timeout.
- `onDestroy()` durante STOP pendente.
- Seletor de ROM e retorno do seletor durante pedido de parada.
- Superficie EGL perdida/recriada e tentativa de teardown com EGL nao atual.
- Nenhum `dlclose` enquanto houver lease/callback nativo ativo.
- Logs reais de `DoRspCycles`, tarefa grafica HLE, `ProcessDList` e swap de frame antes de afirmar renderizacao.

## Decisao desta etapa

Esta etapa registra o contrato de concorrencia e o plano de testes. Nao altera runtime nem habilita `M64CMD_EXECUTE`. A implementacao deve comecar pela classe Kotlin pura e seus testes, sem modificar `onPause()`/ `onDestroy()` ate que o modelo seja exercitado isoladamente.
