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


## Atualização após dois ciclos de carga/descarga no Poco C85 — 2026-11-10

O usuário repetiu o teste isolado na tela **Diagnóstico e testes**:

1. **Carregar Core (teste isolado)** retornou:
   `Core Mupen64Plus carregado com sucesso.`
   `CoreStartup e CoreShutdown encontrados.`
   `Versao do Core: 2.6.0 | API Frontend: 0x020106.`
   `Compatibilidade verificada; inicializacao ainda nao executada.`
2. **Encerrar Core e descarregar biblioteca** retornou:
   `Core descarregado corretamente`.
3. O Core foi carregado novamente após a primeira descarga, com a mesma confirmação de compatibilidade.
4. A segunda chamada de descarga também retornou:
   `Core descarregado corretamente`.

### Conclusão limitada

- Dois ciclos consecutivos de carga → descarga foram confirmados pelo usuário no aparelho.
- O endpoint JNI `CoreNativeBridge.nativeShutdown()` foi resolvido e executado nos dois ciclos.
- O Core 2.6.0 pôde ser carregado novamente após a descarga anterior.
- O teste ocorreu sem inicializar `CoreStartup` e sem executar uma ROM.
- Isto **não** valida STOP aceito, retorno de `M64CMD_EXECUTE`, drenagem de callbacks/leases, teardown gráfico com EGL atual, nem segurança de pausa/destruição da Activity durante emulação.
- `M64CMD_EXECUTE` permanece bloqueado.

### Próxima etapa

Revisar e testar a propriedade process-scoped do shutdown e o protocolo de teardown GL/EGL. Não ligar `onPause()` ou `onDestroy()` a uma nova sequência, não alterar a política de execução e não liberar emulação com base apenas nesses testes isolados.
