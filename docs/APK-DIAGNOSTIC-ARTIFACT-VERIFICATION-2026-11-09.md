# Verificação do APK de diagnóstico — 2026-11-09

## Origem

- Workflow run: 42571113796
- Commit de origem: b87abd27bc2ddfcccac504f68b511689d6bf28a9
- Artifact ID: 13653198091
- Artifact: `n64-android-core-isolated-diagnostic`
- Arquivo interno: `app-debug.apk`
- Tamanho: 3.151.816 bytes
- SHA-256: `f9628da3700776e2121f42c41c7869887e653040ce28a0b7c7f9b6728983af13`

## Conteúdo ARM64 verificado

O APK contém:

- `libSDL2.so`
- `libmupen64plus.so`
- `libmupen64plus-video-GLideN64.so`
- `libmupen64plus-video-android.so` (stub experimental; não é o renderer)
- `libmupen64plus-rsp-hle.so`
- `libn64bridge.so`

## Resultado do workflow

O job `assemble` terminou com conclusão `success`. Foram concluídas as etapas de teste do CoreCallGate, compilação ARM64 de GLideN64 e RSP-HLE, geração de offsets, build do APK, verificação de empacotamento e upload do artefato.

## Limites desta verificação

Este relatório comprova que o APK foi compilado e que as bibliotecas esperadas estão presentes no pacote. Não comprova:

- instalação ou execução no Poco C85;
- execução de `M64CMD_EXECUTE`;
- chamada real a `DoRspCycles`;
- processamento de graphics task HLE por GLideN64;
- apresentação de frame de jogo via `eglSwapBuffers`.

O safety gate de `nativeExecuteCore()` permanece ativo. Não habilitar a emulação até integrar e testar o protocolo de leases com a vida útil dos handles, STOP concorrente, drain, shutdown e ciclo de vida Android/EGL.
