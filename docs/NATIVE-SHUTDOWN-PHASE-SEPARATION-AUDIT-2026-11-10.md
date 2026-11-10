# Auditoria da separacao de STOP/drain e teardown nativo — 2026-11-10

## Estado verificado

- Branch: `main`.
- Commit auditado: `48d99be79775aff38c180126625095053979af78`.
- CI desse commit: sucesso, workflow run [42783722596](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42783722596), job `assemble` concluido.
- O coordenador Kotlin `CoreGlTeardownCoordinator` continua isolado; nao esta ligado a Activity nem ao JNI.
- Nenhuma emulacao foi iniciada por esta etapa.

## O que o C++ ja separa

1. `nativeStopCore()` solicita `M64CMD_STOP` durante um Execute ativo, usando lease Stop e evitando envio duplicado por `g_stopCommandIssued`.
2. `nativeAwaitExecuteReturned()` aguarda o handshake de retorno e declara explicitamente que nao fecha plugin, handle ou EGL.
3. O retorno de `M64CMD_EXECUTE` limpa `g_coreExecuting`, marca o handshake, atualiza `CoreRuntimeCoordinator` e libera a lease Execute.

## Problema ainda existente

`nativeShutdown()` ainda combina em uma unica funcao JNI inicio do encerramento e eventual STOP, espera pelo drain, verificacao EGL, detach de RSP/GFX, fechamento da ROM, shutdown/dlclose dos plugins e Core, e confirmacao de ShutdownComplete.

A guarda EGL impede o pior caso conhecido, mas a API nao expressa que o teardown e uma fase separada e exclusivamente GL.

## Contrato recomendado

### 1. Preparar encerramento (worker, sem GLES)

- Fecha admissao de novas operacoes e entra em StopRequested.
- Se Execute estiver ativo, envia no maximo um STOP protegido por lease.
- Aguarda retorno real de Execute e drain de todas as leases.
- Retorna sucesso somente quando `CoreRuntimeCoordinator::Phase::Drained` estiver confirmado.
- Nao chama EGL, detach, ROM_CLOSE, PluginShutdown, CoreShutdown ou dlclose.
- Timeout/falha preserva handles e deixa a fase latched.

### 2. Finalizar encerramento (thread GL, EGL atual)

- Nao envia STOP e nao espera Execute.
- Exige fase Drained, zero chamadas ativas e `g_coreExecuting == false`.
- Se houver plugin grafico iniciado, exige contexto/display/surface EGL atuais e GLES valido.
- Executa detach/ROM_CLOSE/plugin shutdown/CoreShutdown/dlclose, preservando handles se qualquer passo falhar.
- Marca ShutdownComplete somente apos todos os passos concluidos.
- Valida geracao/token para rejeitar callback GL antigo.

## Riscos a resolver antes de codificar

1. **Falha parcial:** flags ja sao limpos apos cada detach/shutdown. A proxima tentativa precisa continuar a partir dos flags reais sem repetir callbacks concluidos.
2. **Ordem de ROM_CLOSE:** validar contra o contrato Mupen64Plus e plugins fixados se ROM_CLOSE deve ocorrer antes/depois de detach; nao alterar sem verificar.
3. **Mutex e callbacks:** evitar manter `g_coreMutex` durante callback que possa reentrar ou esperar outra thread, salvo garantia da API. Proteger os handles durante toda a operacao.
4. **Concorrencia:** chamadas repetidas de prepare devem aderir a mesma operacao; finalize nao pode ocorrer em paralelo.
5. **Compatibilidade:** `nativeShutdown()` ainda e chamado pelos callbacks atuais da Activity. Nao mudar seu comportamento nem os callbacks ate migrar todos os callers e testar perda/recriacao de Surface.
6. **Core nao iniciado:** manter suportado o caso de biblioteca apenas carregada, sem CoreStartup/plugins/ROM, usado no smoke test do Poco C85.
7. **Recuperacao:** falha EGL/surface nao marca ShutdownComplete nem reabre admissao; deve permitir retry na GL thread com contexto atual.

## Matriz minima de testes

- Core somente carregado: prepare/finalize sem plugin iniciado.
- Core inicializado, sem ROM: drain e shutdown.
- ROM aberta/plugins anexados, sem Execute: prepare sem GLES; finalize requer EGL se GFX iniciado.
- Execute ativo: STOP unico, retorno confirmado e drain antes do finalize.
- Execute retorna naturalmente durante prepare: nenhum STOP redundante.
- STOP recusado/timeout: finalize bloqueado.
- EGL ausente/surface perdida: sem teardown grafico e sem dlclose.
- Falha em cada callback: flags/handles preservados para retry.
- Chamadas concorrentes: uma unica sequencia efetiva.
- Callback GL de geracao antiga: rejeitado.

## Decisao desta etapa

Auditoria documental. Nao altera `nativeShutdown()`, `MainActivity`, JNI, plugins ou o botao Execute. A proxima implementacao deve extrair helpers C++ testaveis para prepare/drain e finalize/GL, mantendo o endpoint legado intacto ate a migracao completa.
