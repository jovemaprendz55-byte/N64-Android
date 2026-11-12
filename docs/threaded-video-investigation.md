# Investigação: Threaded Video do GLideN64 no Android

## Estado inicial

Esta branch experimental parte de `main` e não altera a branch principal.

O app atualmente usa:
- `GLSurfaceView` como proprietária do contexto EGL.
- `GLSurfaceView.Renderer` para criar o contexto e inicializar o plugin.
- `M64CMD_EXECUTE` executado sincronamente na thread GL por meio de `queueEvent`.
- `RENDERMODE_WHEN_DIRTY`.
- O callback Android `VidExtFuncGLSwapBuf` chama `eglSwapBuffers` no contexto/surface atuais da thread GL.

No workflow de build, o patch Android do GLideN64 força:
```cpp
FunctionWrapper::setThreadedMode(0);
```
A chamada normal `FunctionWrapper::setThreadedMode(config.video.threadedVideo)` é mantida apenas fora do Android. Isso é intencional: o contexto EGL pertence à thread GL do `GLSurfaceView`.

## Por que não basta ativar a opção

Ativar apenas `config.video.threadedVideo = 1` ou remover o patch acima não reproduz o comportamento do Mupen64Plus FZ. O wrapper threaded do GLideN64 pode executar comandos OpenGL em outra thread; essa thread precisaria possuir um contexto EGL válido e compatível. O contexto atual é gerenciado pelo `GLSurfaceView`, e o callback de apresentação pressupõe que o contexto e a surface estejam atuais na thread que executa o Core.

Uma mudança ingênua pode causar:
- chamadas GLES sem contexto atual;
- troca de contexto/surface em threads diferentes;
- deadlock entre a thread do Core, fila GL e callback de swap;
- falhas na recriação da surface ou no pause/resume;
- shutdown com contexto EGL ausente ou pertencente a outra thread.

## Diagnóstico de desempenho já obtido

Nos testes recentes de GoldenEye:
- `findBuffer` e `copyFromRDRAM` ficaram praticamente sem custo.
- `copyTexturedRect` e `overscanDraw` ficaram abaixo de 1 ms.
- `swapBuffers` mediu frequentemente cerca de 9–16 ms, com picos acima de 30–40 ms.
- `ProcessDList` também apresentou picos isolados elevados.
- A medição de `renderBuffer` inclui `swapBuffers`; não somar os dois como custos independentes.

Portanto, o alvo da investigação é a fila/apresentação GL e a sincronização com o driver/SurfaceFlinger, sem presumir que threaded video resolverá sozinho todos os engasgos.

## Plano seguro para esta branch

1. Inspecionar o wrapper threaded do GLideN64 e o backend Android do Mupen64Plus FZ, identificando quem cria, compartilha, torna atual e destrói cada EGLContext/EGLSurface.
2. Mapear a sincronização da fila: limite de comandos pendentes, barreiras, tratamento de swap e encerramento.
3. Comparar esse modelo com o contrato atual do `GLSurfaceView`.
4. Só então escolher uma arquitetura:
   - manter o contexto do `GLSurfaceView` e testar uma fila de comandos que execute GL na própria thread proprietária; ou
   - migrar explicitamente para gerenciamento EGL próprio, com contexto compartilhado, transferência de ownership, sincronização e ciclo de vida completos.
5. Fazer uma alteração mínima e isolada, com opção de retorno ao modo atual.
6. Compilar e testar no Poco C85/Android 16 antes de considerar qualquer merge.

## Critérios para aceitar o experimento

- Nenhuma chamada OpenGL ocorre numa thread sem contexto atual.
- O contexto/surface usados no swap são os esperados.
- Pause, resume, rotação/recriação de surface e encerramento não causam deadlock ou perda de contexto.
- GoldenEye apresenta FPS e tempos de frame melhores de forma repetível, sem regressões visuais ou de áudio.
- O modo atual permanece disponível como fallback.

## Regra desta experiência

Não habilitar threaded video no Android apenas mudando uma flag. Nenhuma mudança deve ser mesclada em `main` sem teste real e autorização explícita.
