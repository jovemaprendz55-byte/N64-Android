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


## Mapeamento do ciclo de vida no nosso app

A leitura do código de `main` confirma que a diferença de arquitetura começa antes do primeiro frame:

- `MainActivity.showOpenGlTest()` cria o `GLSurfaceView`, configura GLES 3 e instala o `Renderer`.
- Em `Renderer.onSurfaceCreated()`, o app verifica o EGL atual e chama a sequência JNI que registra a Video Extension e carrega o GLideN64.
- `nativeLoadGfxPlugin()` exige `eglGetCurrentContext()`, `eglGetCurrentDisplay()`, `eglGetCurrentSurface(EGL_DRAW)` e `glGetString(GL_VERSION)` válidos; em seguida chama `PluginStartup` nessa mesma thread.
- A abertura da ROM e o início de `M64CMD_EXECUTE` também são enviados por `GLSurfaceView.queueEvent`. O Core síncrono ocupa a thread GL.
- O callback de swap Android usa o EGL que já está atual na thread GL; não cria nem compartilha outro contexto.
- No `onDestroy()`, o app agenda `nativeShutdown()` pela thread GL e só pausa o `GLSurfaceView` depois de confirmar o encerramento. O código nativo também recusa teardown gráfico quando o contexto não está atual na thread chamadora.

Logo, uma migração fiel ao FZ precisa mover em conjunto **a criação/posse do EGL, a inicialização do GLideN64, os callbacks de vídeo, a execução do Core e o teardown**. Mover apenas `M64CMD_EXECUTE` ou apenas as chamadas GLES deixaria partes do plugin usando threads/contextos incompatíveis.

## Arquitetura-alvo proposta (ainda não implementada)

1. Criar um componente nativo de vídeo Android que possua `EGLDisplay`, `EGLConfig`, `EGLContext`, `EGLSurface` e referência retida a `ANativeWindow`.
2. Definir uma única thread proprietária do contexto. Essa thread executaria as operações de contexto/surface e as chamadas GL encaminhadas pelo wrapper.
3. Fazer os callbacks `VidExt_*` encaminharem criação, resize, swap e destruição para o proprietário EGL, com sincronização explícita e sem bloquear enquanto segura mutex necessário ao callback.
4. Reorganizar a inicialização: aguardar uma janela nativa disponível, criar EGL no proprietário, então chamar `PluginStartup` e anexar os plugins; não chamar `PluginStartup` a partir de `GLSurfaceView.onSurfaceCreated`.
5. Reorganizar execução e parada para que o Core não tente destruir EGL enquanto a thread de renderização ainda possui comandos pendentes.
6. Lidar com `surfaceDestroyed/surfaceCreated`, pausa, retomada, rotação e encerramento como estados explícitos; nunca manter um `ANativeWindow` depois de liberar sua referência.
7. Só depois dessa prova de conceito considerar ativar o wrapper threaded e comparar os resultados.

**Decisão por enquanto:** não alterar o fluxo Kotlin/C++ de renderização nesta etapa. A investigação provou que copiar apenas a flag do FZ seria inseguro. A próxima implementação deve ser uma migração de ownership EGL planejada, não um ajuste de configuração.
