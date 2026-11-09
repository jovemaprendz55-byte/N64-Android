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

## Atualizacao apos o teste concorrente — commit 3100dfb

- Workflow [42678007828](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42678007828) concluiu com sucesso.
- O job executou `Run Kotlin JVM unit tests` e `Build debug APK`, ambos com sucesso.
- O conjunto agora inclui um teste com 32 workers sincronizados por latch, alternando pedidos MANUAL_STOP e ACTIVITY_PAUSE. Todos devem aderir a uma unica geracao.
- Isso valida a exclusao mutua do modelo puro sob concorrencia; nao valida JNI, Core real, Activity, EGL ou destruicao de Surface.

## Revisao adicional do ponto de integracao

A leitura atual de `MainActivity.kt` confirma que os metodos JNI continuam declarados como metodos externos de instancia da Activity. O worker manual STOP captura implicitamente a Activity para chamar `nativeStopCore()` e `nativeAwaitExecuteReturned()`. Portanto, mover esse worker para um executor global sem alterar a propriedade da ponte JNI criaria risco de lifetime e retencao de Activity.

Tambem permanece o problema mais urgente: `onPause()` chama `nativeShutdown()` sincronicamente na thread principal antes de agendar o encerramento grafico. Isso pode bloquear a UI e mistura pedido de STOP/drain com teardown. `onDestroy()` ainda espera sincronicamente pelo evento GL. Nao alterar esses callbacks isoladamente antes de definir o dono de lifetime da ponte nativa e a politica para perda da Surface.

## Proxima unidade de trabalho segura

1. Extrair uma interface Kotlin pequena para as operacoes JNI usadas pelo lifecycle, sem mudar as assinaturas JNI ainda.
2. Criar fake deterministico e testes de concorrencia para STOP, retorno de EXECUTE, timeout e callbacks tardios.
3. Decidir e implementar um proprietario estavel da ponte JNI que nao seja a Activity. Auditar nomes JNI e assinaturas C++ antes de alterar.
4. So entao ligar o coordenador a um executor serial e a um callback GL com validacao de geracao e EGL.
5. Nao mudar `onPause()` / `onDestroy()` e nao habilitar EXECUTE ate os testes de integracao cobrirem a perda real da Surface.

## Decisao desta etapa

Esta etapa registra o contrato de concorrencia e o plano de testes. Nao altera runtime nem habilita `M64CMD_EXECUTE`. A implementacao deve comecar pela classe Kotlin pura e seus testes, sem modificar `onPause()`/ `onDestroy()` ate que o modelo seja exercitado isoladamente.


## Atualizacao: contrato isolado da ponte de lifecycle

- CI dos commits `1027b315` e `85dc406a` concluiu com sucesso.
- A maquina de estados agora mantém a geração bloqueada após timeout de retorno do Execute, perda da superfície e timeout de teardown GL. Isso evita abrir uma nova geração sem recuperação explícita.
- Criado `CoreLifecycleBridge.kt` com contrato mínimo `requestStop()` / `awaitExecuteReturned(timeoutMs)`.
- Criado `CoreLifecycleBridgeTest.kt` com fake determinístico para ordem STOP → espera e rejeição de STOP.
- **Ainda não conectado à MainActivity nem ao JNI.** Isso é intencional: os métodos JNI ainda são exports de instância `MainActivity`; migrar só a interface sem migrar corretamente a propriedade/lifetime do objeto nativo manteria a captura da Activity.
- Auditoria encontrou 16 exports JNI nomeados `Java_com_jsr_n64android_MainActivity_*`. Uma futura migração precisa decidir se move todos para uma classe bridge estável ou se cria uma ponte JNI separada apenas para operações de lifecycle, atualizando e verificando os símbolos C++ e as declarações Kotlin em conjunto.
- Não alterar `onPause()`, `onDestroy()`, shutdown GL ou habilitar Execute nesta etapa.


## Atualizacao: política de STOP isolada e testável

- CI dos commits `1f5af681` e `0ab79d0f` concluiu com sucesso: contrato da ponte e fake determinístico compilam/testam.
- Criado `CoreStopCoordinator.kt`: aplica a política de STOP aceito → aguardar retorno do Execute; STOP recusado → não aguardar. Não executa shutdown, detach de plugins ou operações EGL.
- Criado `CoreStopCoordinatorTest.kt`: cobre STOP aceito, STOP já enviado, STOP recusado e timeout negativo limitado a zero.
- O coordenador ainda não está conectado à Activity. A integração fica para depois da migração da propriedade JNI para um objeto de vida útil estável e da auditoria de todos os símbolos JNI.
- Não alterar lifecycle Android, não mover `nativeShutdown()` para worker, não habilitar `M64CMD_EXECUTE`.


## Atualização após smoke test da ponte JNI no Poco C85 — 2026-11-09

- O usuário instalou o APK e pressionou **Parar emulação** com Core/ROM abertos, mas sem iniciar Execute.
- Resposta observada: `Parada recusada: nao existe Execute ativo ou o ciclo ja foi encerrado.`
- A confirmação seguinte foi `Aguardagem nao iniciada: o STOP nao foi confirmado como enviado.`
- Isso confirma que o símbolo JNI de `CoreNativeBridge.nativeStopCore()` foi resolvido no aparelho e que o coordenador não iniciou espera indevida após STOP recusado.
- **Limite:** o teste não chamou `nativeAwaitExecuteReturned()`, não percorreu STOP aceito e não provou execução ou renderização.
- Registro detalhado: [CORE-NATIVE-BRIDGE-DEVICE-SMOKE-TEST-2026-11-09.md](CORE-NATIVE-BRIDGE-DEVICE-SMOKE-TEST-2026-11-09.md).
- Próximo passo: avançar para a coordenação serial do lifecycle com testes JVM, sem ainda alterar `onPause()`/`onDestroy()` nem habilitar `M64CMD_EXECUTE`.


## Atualização: intenção de lifecycle mais restritiva — 2026-11-09

- A máquina de estados agora reúne pedidos simultâneos na mesma geração e atualiza a intenção registrada para a mais restritiva.
- Prioridade: `ACTIVITY_DESTROY` > `ACTIVITY_PAUSE` > `SCREEN_CHANGE` > `ROM_REPLACEMENT` > `MANUAL_STOP`.
- Um pedido manual seguido de pausa/destruição não cria outra geração nem perde a intenção de encerramento.
- Um pedido menos restritivo não pode rebaixar uma intenção de destruição já registrada.
- Testes JVM adicionados para upgrade e não-downgrade da intenção.
- Isso altera somente o modelo Kotlin puro; não liga os callbacks Android, não envia JNI, não chama shutdown e não habilita Execute.


## Verificação da correção da expectativa de prioridade — 2026-11-09

- O CI do commit `c5cb211` falhou em `CoreLifecycleStateMachineTest.lifecycleRequestsJoinManualStopAlreadyInFlight`: o teste ainda esperava `MANUAL_STOP` depois de um pedido `ACTIVITY_DESTROY`.
- A implementação já aplicava corretamente a regra documentada de prioridade; a expectativa antiga do teste estava incorreta.
- Corrigida somente a asserção para esperar `ACTIVITY_DESTROY`, sem alterar runtime: commit `200abaf29ca41f1f16d251ddf6e25fc692cba214`.
- CI [42686138623](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42686138623) concluiu com sucesso; job `assemble` verde.
- O resultado valida os testes JVM e a compilação do APK de debug. Não valida STOP aceito no dispositivo, retorno de Execute, teardown GL/EGL ou renderização.
- Próxima etapa permanece a criação e teste isolado do coordenador serial; callbacks `onPause()`/`onDestroy()` continuam sem alteração e `M64CMD_EXECUTE` permanece bloqueado.
