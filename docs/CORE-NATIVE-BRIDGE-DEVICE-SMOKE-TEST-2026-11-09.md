# Teste de fumaça da ponte JNI do lifecycle — 2026-11-09

## Resultado observado no Poco C85

O usuário instalou o APK de diagnóstico e executou o botão **Parar emulação** depois de carregar o Core e abrir uma ROM, sem iniciar `M64CMD_EXECUTE`.

Texto retornado pelo aplicativo:

```text
Pedido de parada: Parada recusada: nao existe Execute ativo ou o ciclo ja foi encerrado.
Confirmação de retorno: Aguardagem nao iniciada: o STOP nao foi confirmado como enviado.
```

## O que este teste confirma

- O botão alcançou o caminho Kotlin `CoreStopCoordinator`.
- A chamada JNI de `CoreNativeBridge.nativeStopCore()` foi resolvida e retornou uma resposta nativa, sem `UnsatisfiedLinkError`, falha de símbolo ou crash observado.
- O resultado de STOP recusado foi tratado corretamente: o coordenador não chamou `nativeAwaitExecuteReturned()` quando não houve confirmação de STOP enviado.
- O aplicativo permaneceu aberto durante este caminho.

## O que este teste NÃO confirma

- Não confirma o caminho JNI de `nativeAwaitExecuteReturned()`, porque a espera não foi iniciada.
- Não confirma STOP aceito durante um `M64CMD_EXECUTE` real.
- Não confirma retorno síncrono de Execute, drain de leases, teardown dos plugins, ciclo de EGL ou renderização.
- Não autoriza habilitar o botão de execução.

## Próxima etapa segura

1. Preservar `M64CMD_EXECUTE` bloqueado.
2. Implementar e testar isoladamente a coordenação serial entre STOP manual, `onPause()`, `onDestroy()`, troca de tela e seletor de ROM.
3. Manter STOP/espera em worker e reservar `nativeShutdown()`/teardown gráfico à GL thread com EGL atual.
4. Executar CI e revisar o diff antes de qualquer novo APK.
5. Só depois planejar um teste controlado do caminho STOP aceito, com proteção contra travamento e confirmação explícita do retorno de Execute.
