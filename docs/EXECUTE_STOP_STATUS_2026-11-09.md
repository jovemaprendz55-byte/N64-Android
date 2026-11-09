# Estado do ciclo EXECUTE/STOP — 2026-11-09

## Estado confirmado

- Repositório: `jovemaprendz55-byte/N64-Android`, branch `main`.
- Core Mupen64Plus: pin `a9ca8ec0b7decd17390c76c6341cf423d5d79321`.
- RSP-HLE: pin `dd09c7dcdc6d2cff584aacb9d519600d47d61039`.
- Renderer: GLideN64, compilado para ARM64.
- O usuário reportou que a ROM abriu, GFX e RSP foram anexados e `InitiateGFX`/`InitiateRSP` foram concluídos. Naquele teste a emulação ainda não havia começado.
- CI do commit `3cc3fa4eefc2ff93ec67e398bf6a6930c42e0656`: run 42586704767, resultado `success`; job `assemble`, 32/32 etapas. Artifact: `n64-android-core-isolated-diagnostic`.
- Isso comprova build e testes automatizados, não execução real no aparelho nem frame renderizado.

## Handshake de readiness

O workflow modifica, somente durante o build, o Core pinado em `src/device/r4300/r4300_core.c`. Logo depois de:

```c
*r4300_stop(r4300) = 0;
g_rom_pause = 0;
```

é inserido:

```c
DebugMessage(M64MSG_INFO, "N64ANDROID_R4300_READY_20261109");
```

O workflow exige que o bloco original apareça exatamente uma vez, aplica o patch e verifica o marcador. O callback JNI identifica a mensagem e sinaliza `CoreRunLoopHandshake`.

Arquivos principais:
- `app/src/main/cpp/core_run_loop_handshake.h`
- `tests/native/core_run_loop_handshake_test.cpp`
- `app/src/main/cpp/core_runtime_coordinator.h`
- `tests/native/core_runtime_coordinator_test.cpp`
- `app/src/main/cpp/native-lib.cpp`
- `.github/workflows/android.yml`

## Protocolo atual

1. `nativeExecuteCore()` exige contexto EGL atual e ROM + GFX + RSP anexados.
2. Reserva o slot Execute e localiza `CoreDoCommand`.
3. Reseta o handshake antes de publicar a fase `Executing`, evitando aceitar readiness de execução anterior.
4. Chama `CoreDoCommand(M64CMD_EXECUTE)` sem manter `g_coreMutex`; a chamada é síncrona.
5. O Core emite o marcador após limpar STOP. Só então STOP é elegível.
6. `nativeStopCore()` espera até 1500 ms pelo marcador ou pelo retorno de EXECUTE. Em timeout, não envia STOP.
7. Ao retornar EXECUTE, o lease é liberado. Retorno natural cancela STOP pendente.
8. Shutdown aguarda readiness/retorno, envia STOP apenas quando necessário, drena leases e só depois desmonta plugins/ROM/Core.
9. Teardown de GLideN64 exige EGL atual; em timeout ou contexto inválido, os handles permanecem carregados.

## Estado da interface

O botão de EXECUTE permanece desabilitado como **“Emulação bloqueada por segurança”**. A função JNI existe, mas a interface ainda não oferece um caminho de teste ativo para chamá-la. O botão “Parar emulação” não comprova que há um loop ativo.

Não habilitar o botão só porque o CI passou.

## Próximos testes no Poco C85

Executar primeiro um teste controlado, com logs capturados:
1. Confirmar contexto EGL/GLES 3.x atual na thread GL.
2. Abrir ROM e confirmar anexação GFX/RSP.
3. Acionar EXECUTE em thread GL sem bloquear a thread de UI.
4. Confirmar log `N64ANDROID_R4300_READY_20261109`.
5. Solicitar STOP a partir da thread de UI e confirmar retorno síncrono de EXECUTE na thread GL.
6. Repetir com STOP antes do marcador, timeout, retorno natural e shutdown.
7. Só depois rastrear o pipeline de jogo: `DoRspCycles` → tarefa RSP/HLE → `ProcessDlistList`/RDP → GLideN64 `ProcessDList` → callback de swap / `eglSwapBuffers`.

## Critério para habilitar EXECUTE

- CI verde no commit atual.
- Logs reais do aparelho confirmando o marcador após o reset.
- STOP comprovadamente encerra o loop sem deadlock, crash ou teardown prematuro.
- Shutdown e recriação da superfície EGL testados.
- Só declarar imagem de jogo após evidência de frame real na tela.

## Limites conhecidos

- O marcador prova que o loop R4300 chegou ao ponto posterior ao reset da flag STOP; não prova que uma tarefa gráfica foi executada.
- Um `eglSwapBuffers` isolado pode apenas apresentar um buffer vazio; não prova renderização de ROM.
- Ainda não há confirmação no dispositivo de `M64CMD_EXECUTE`, `DoRspCycles`, processamento de display list ou frame visível.
