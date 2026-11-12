# Investigação: Threaded Video do GLideN64 no Android

## Estado inicial do nosso app

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

## O que o Mupen64Plus FZ realmente faz

Fontes públicas examinadas no repositório `fzurita/mupen64plus-ae`, branch `fz-master`:

- `mupen64plus-video-gliden64/upstream/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_Wrapper.cpp`
- `mupen64plus-video-gliden64/upstream/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_Wrapper.h`
- `ae-bridge/src/ae_bridge.cpp`

O modelo do FZ é diferente do nosso:

1. `FunctionWrapper::setThreadedMode(1)` cria uma `m_commandExecutionThread` que executa `commandLoop()`.
2. Chamadas GL são colocadas em `m_commandQueue`; a thread chamadora espera a conclusão de cada comando por `waitOnCommand()`. Portanto, não é correto descrever cada chamada GL como totalmente assíncrona: a execução é transferida para a thread de comandos, mas a chamada normalmente só retorna depois que o comando foi executado.
3. As chamadas de vídeo do Core também passam pelo wrapper. `CoreVideo_Init`, `CoreVideo_SetVideoMode`, leitura/escrita de atributos e `CoreVideo_GL_SwapBuffers` são executadas como comandos na thread de comandos quando o modo threaded está ativo.
4. No bridge Android do FZ, `VidExtFuncSetMode` cria o `EGLContext` e a `EGLSurface` e chama `eglMakeCurrent`. Como essa operação é despachada pelo wrapper, a inicialização do contexto acontece na thread de comandos, que passa a executar o GL.
5. `VidExtFuncGLSwapBuf` usa um mutex (`nativeWindowAccess`) para proteger a janela nativa e a surface; faz a recriação da surface e a troca de contexto quando necessário, e chama `eglSwapBuffers`.
6. O wrapper conta swaps pendentes (`m_swapBuffersQueued`) e define `MAX_SWAP = 2`; a rotina `WaitForSwapBuffersQueued()` aguarda quando a fila excede esse limite. Os comandos de swap também são enfileirados pelo wrapper.

**Conclusão importante:** o FZ não está simplesmente ativando uma flag dentro de um `GLSurfaceView`. Ele tem um bridge EGL próprio e encaminha inclusive as operações de criação do contexto para a thread de comandos. A thread que possui o contexto é parte fundamental da arquitetura.

## Por que não basta ativar a opção no nosso app

Ativar apenas `config.video.threadedVideo = 1` ou remover o patch acima não reproduz o comportamento do FZ. Nosso contexto atual é criado e gerenciado pelo `GLSurfaceView`; o callback Android de swap pressupõe que o contexto e a surface estejam atuais na thread GL do `GLSurfaceView`. Se o wrapper passar a executar GLES na sua thread privada sem migrar também a propriedade do contexto, haverá chamadas sem contexto atual.

Uma mudança ingênua pode causar:
- chamadas GLES sem contexto atual;
- `eglMakeCurrent` concorrente ou na thread errada;
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

## Decisão técnica e plano seguro

A arquitetura do FZ exige gerenciamento explícito de EGL. A opção mais fiel seria substituir a propriedade EGL do `GLSurfaceView` por um bridge Android/EGL próprio, no qual:
- a thread de comandos do GLideN64 inicializa e possui o contexto EGL;
- criação/recriação/destruição de surface e `ANativeWindow` são serializadas;
- pause/resume e shutdown aguardam a thread de GL terminar antes de destruir recursos;
- os callbacks de swap usam exclusivamente o contexto/surface atuais dessa thread.

Isso é uma mudança arquitetural ampla e não deve ser tentada como patch pequeno. Uma alternativa é manter o `GLSurfaceView` e experimentar uma fila que execute comandos na própria thread proprietária, mas isso **não é a arquitetura threaded do FZ** e não garante redução do tempo de swap.

Próximas etapas:
1. Mapear os pontos de inicialização e encerramento do Core no nosso código e definir um contrato explícito de propriedade EGL.
2. Desenhar a transição do `GLSurfaceView` para EGL próprio, incluindo surface lifecycle e sincronização.
3. Criar testes para criação, pause, resume, recriação da surface e encerramento antes de alterar a renderização.
4. Implementar primeiro uma prova de conceito mínima em branch isolada, com fallback para o modo atual.
5. Compilar e testar no Poco C85/Android 16; comparar FPS, tempos de frame, estabilidade e áudio.

## Critérios para aceitar o experimento

- Nenhuma chamada OpenGL ocorre numa thread sem contexto atual.
- O contexto/surface usados no swap são os esperados.
- Pause, resume, rotação/recriação de surface e encerramento não causam deadlock ou perda de contexto.
- GoldenEye apresenta FPS e tempos de frame melhores de forma repetível, sem regressões visuais ou de áudio.
- O modo atual permanece disponível como fallback.

## Regra desta experiência

Não habilitar threaded video no Android apenas mudando uma flag. Nenhuma mudança deve ser mesclada em `main` sem teste real e autorização explícita.
