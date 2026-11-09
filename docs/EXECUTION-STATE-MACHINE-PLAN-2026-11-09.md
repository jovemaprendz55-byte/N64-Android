# Plano de estado seguro para executar e parar o Core — 2026-11-09

## Estado verificado no código atual

Revisão feita sobre `main`, commit-base `1330a2700950e7648d838858a9c483ca9853a39f`.

- `nativeExecuteCore()` continua deliberadamente bloqueado e retorna antes de chamar `M64CMD_EXECUTE`.
- `g_coreExecuting` existe como `std::atomic<bool>`, mas o caminho atual não o define como ativo porque não existe execução real.
- `nativeOpenRomAndAttachGfx()`, carregamento dos plugins e operações de inicialização usam `g_coreMutex`; isso protege a configuração atual, mas não deve ser mantido durante o loop síncrono de emulação.
- `nativeStopCore()` usa `g_coreMutex` e envia `M64CMD_STOP`. O comando é assíncrono; seu retorno não prova que o loop já terminou.
- `nativeShutdown()` recusa o descarregamento enquanto `g_coreExecuting` estiver ativo e exige contexto EGL atual quando o GFX está iniciado.
- Em Kotlin, `executeCoreOnGlThread()` está sem call site e o botão de execução permanece desativado.
- `onPause()` pausa imediatamente a GLSurfaceView. `onDestroy()` agenda shutdown na thread GL e espera no máximo 1500 ms. Esse timeout não pode ser tratado como confirmação de parada/limpeza.

## Regra fundamental

Não habilitar `M64CMD_EXECUTE` só por adicionar uma chamada JNI. A chamada é síncrona e pode ocupar a thread GL até o Core retornar. O ciclo de vida precisa reconhecer a conclusão real dessa chamada, não apenas o retorno de `M64CMD_STOP` ou o fim de um timeout.

## Máquina de estados proposta

Estados mínimos:

1. `IDLE`: nenhum comando EXECUTE ativo.
2. `STARTING`: validação concluída e chamada EXECUTE prestes a entrar no Core.
3. `RUNNING`: o Core confirmou que a emulação entrou no estado ativo.
4. `STOP_REQUESTED`: pedido STOP enviado; o Core ainda pode estar executando.
5. `STOPPED`: a chamada síncrona EXECUTE retornou e os callbacks de fechamento do Core terminaram.
6. `SHUTTING_DOWN`: detach/ROM close/plugin shutdown/CoreShutdown permitidos somente após STOPPED.

A implementação pode usar enum protegido por mutex e/ou atomics, mas todas as transições devem ser documentadas e testadas. Não usar um único booleano para confundir pedido de parada com parada concluída.

## Invariantes nativos obrigatórios

- Validar Core iniciado, ROM aberta, GFX/RSP carregados e anexados e EGL atual antes de aceitar execução.
- Sob `g_coreMutex`, recusar execução duplicada e transicionar para STARTING. Capturar os ponteiros/funções necessários enquanto os handles estão protegidos.
- Liberar `g_coreMutex` antes de chamar `CoreDoCommand(M64CMD_EXECUTE)`. Nunca manter esse mutex durante o loop síncrono: a thread de UI precisa conseguir solicitar STOP.
- Marcar STOPPED somente depois que `CoreDoCommand(M64CMD_EXECUTE)` retornar. O retorno de `M64CMD_STOP` sozinho nunca libera recursos.
- `nativeStopCore()` deve permanecer chamável de outra thread, enviar STOP sem depender da fila GL e registrar STOP_REQUESTED. Deve ser idempotente para pedidos repetidos.
- Tratar cuidadosamente a corrida entre STARTING e STOP: um STOP solicitado antes de o Core entrar no estado ativo deve ser retido e entregue assim que o estado ativo for confirmado; não assumir que um STOP antecipado foi consumido.
- Enquanto STARTING/RUNNING/STOP_REQUESTED, recusar ROM_OPEN/ROM_CLOSE, attach/detach, novo carregamento, shutdown de plugin, `dlclose` e `CoreShutdown`.
- Preservar handles, ROM e plugins até STOPPED. Não usar `g_coreMutex` para serializar a espera pelo loop.
- Não marcar STOPPED em caso de exceção JNI, timeout ou simples aceitação do comando STOP.

## Coordenação Android necessária

- A execução, se futuramente habilitada, deve ser chamada na GL thread com EGL atual.
- O pedido STOP vem da thread UI e não pode ser enfileirado atrás do EXECUTE na GL thread.
- `onPause()` deve solicitar STOP e aguardar a confirmação assíncrona de STOPPED antes de pausar a GLSurfaceView. Não bloquear a UI esperando indefinidamente.
- `onDestroy()` deve solicitar STOP e só agendar detach/shutdown na GL thread depois de STOPPED. Se a Activity for destruída antes da confirmação, manter as bibliotecas carregadas; não interpretar timeout como limpeza concluída.
- Tratar perda/recriação do EGL context separadamente. Nunca chamar callbacks do GLideN64 sem o contexto EGL apropriado.
- `showHomeScreen()` e troca de tela também não devem pausar/remover a GLSurfaceView enquanto o Core estiver ativo.

## Matriz mínima de testes antes de liberar o botão

| Cenário | Resultado obrigatório |
|---|---|
| Execute com ROM/plugins ausentes | Recusado antes de entrar no Core |
| Duplo toque em executar | Uma única chamada EXECUTE |
| STOP durante execução | STOP_REQUESTED; recursos continuam carregados |
| STOP repetido | Sem crash e sem segunda transição indevida |
| STOP concorrente com retorno natural do EXECUTE | Uma única transição para STOPPED |
| Pausar Activity durante execução | STOP solicitado; GLSurfaceView só pausa após STOPPED |
| Destruir Activity durante execução | Sem detach/dlclose/CoreShutdown prematuros |
| EGL perdido após STOP | Shutdown gráfico recusado e bibliotecas preservadas |
| ROM_CLOSE/novo attach durante execução | Recusado |
| Retorno de EXECUTE | Só então STOPPED e cleanup pode começar |

## Critérios para desbloquear a execução

A máquina de estados e o coordenador Android precisam estar implementados e revisados; os testes de concorrência/lifecycle acima precisam passar; o build precisa passar; e um teste no aparelho deve demonstrar parada segura. Depois disso, ainda será necessário confirmar separadamente o primeiro `DoRspCycles`, primeira tarefa gráfica HLE, primeiro `ProcessDList`, `eglSwapBuffers` bem-sucedido e imagem real do jogo. A simples abertura da ROM e anexação dos plugins não satisfaz esses critérios.

## Próxima tarefa de engenharia

Implementar primeiro a máquina de estados nativa e uma consulta confiável de estado para Kotlin, sem habilitar o botão. Em seguida implementar o coordenador de pausa/destruição Android. Só depois fazer revisão de corrida STARTING/STOP e testes automatizados/instrumentados. Manter o gate de execução fechado até todos os critérios acima serem atendidos.


## Auditoria complementar do código em `main` — 2026-11-09

Revisão posterior do código atual confirmou detalhes que precisam ser tratados antes da primeira implementação:

- O estado nativo real ainda é somente `std::atomic<bool> g_coreExecuting{false}`; não existe enum de ciclo de vida nem estado STOP_REQUESTED.
- `nativeExecuteCore()` retorna pelo safety gate antes de obter `g_coreMutex` ou resolver/chamar `CoreDoCommand`. Portanto não há hoje uma chamada EXECUTE oculta ou ativa.
- `nativeStopCore()` mantém `g_coreMutex` durante a chamada a `CoreDoCommand(M64CMD_STOP)`. Isso é aceitável apenas no estado atual, em que EXECUTE está bloqueado. Não copiar esse padrão automaticamente para a futura implementação: a chamada STOP precisa ser compatível com o ciclo síncrono EXECUTE e com a proteção de vida útil do handle.
- `nativeShutdown()` mantém o mesmo mutex durante detach, ROM_CLOSE, PluginShutdown, dlclose e CoreShutdown. Assim, o mutex também funciona hoje como proteção da vida útil dos handles.
- A solução futura não pode simplesmente liberar o mutex e chamar uma função Core guardada em ponteiro local: shutdown poderia descarregar o handle entre a captura e a chamada. É necessário introduzir um protocolo explícito de chamadas em andamento/lease do Core, ou outro mecanismo equivalente que impeça shutdown/dlclose até todas as chamadas Core terminarem.
- O protocolo deve permitir STOP concorrente com EXECUTE sem permitir STOP concorrer com CoreShutdown/dlclose. A reserva de chamada STOP precisa ser obtida sob sincronização e liberada depois do retorno do comando.
- Todas as operações mutáveis precisam rejeitar estados STARTING, RUNNING, STOP_REQUESTED e SHUTTING_DOWN: inicialização, carregamento de GFX/RSP, abertura de ROM, registro de Video Extension quando aplicável, detach, shutdown e descarregamento.
- A implementação Kotlin atual ainda pausa a GLSurfaceView diretamente em `onPause()`; o coordenador de ciclo de vida não foi implementado. O `onDestroy()` continua usando espera limitada de 1500 ms e não tem confirmação de STOPPED porque EXECUTE permanece bloqueado.

### Decisão de implementação

Não foi alterado o código nativo nesta etapa: criar um enum sem resolver a vida útil concorrente do ponteiro `CoreDoCommand` daria uma falsa impressão de segurança. A próxima alteração de código deve introduzir primeiro o protocolo de lease/chamadas ativas e testes de concorrência; só então adicionar as transições completas e o coordenador Kotlin. O gate `M64CMD_EXECUTE` permanece fechado.
