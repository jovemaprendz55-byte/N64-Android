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


## Contrato de threads e máquina de estados (projeto da migração)

A inspeção adicional dos coordenadores Kotlin/C++ confirma que o app já separa duas responsabilidades que não podem ser misturadas na migração:

- 'CoreSerialLifecycleCoordinator' executa STOP/drain no worker e mantém uma única operação ativa.
- 'CoreGlTeardownCoordinator' só agenda o encerramento nativo depois que 'M64CMD_EXECUTE' retornou; o guard EGL é consultado dentro da ação despachada na thread GL.
- 'CoreRuntimeCoordinator' fecha a admissão de chamadas, espera o Core drenar e só permite reset depois de teardown concluído.

Esses coordenadores são uma boa base de lifecycle, mas o contrato atual pressupõe que a thread GL seja a do 'GLSurfaceView'. Não se deve reaproveitar o dispatcher atual como se ele já fosse o proprietário EGL da nova arquitetura.

### Estados propostos para o bridge EGL

A implementação deve modelar explicitamente os estados abaixo. São estados-alvo de projeto, não estados já implementados:

| Estado | Significado | Operações permitidas |
|---|---|---|
| 'NO_WINDOW' | Nenhuma janela Android retida | Aguardar 'surfaceCreated' |
| 'WINDOW_AVAILABLE' | 'ANativeWindow' retida, sem EGL atual | Criar/recriar EGL na thread proprietária |
| 'EGL_READY' | Display, config, context e surface válidos e atuais na thread proprietária | Inicializar plugin de vídeo |
| 'PLUGIN_READY' | 'PluginStartup' terminou com EGL atual | Anexar plugins e abrir ROM |
| 'ROM_READY' | ROM aberta e plugins anexados | Reservar/iniciar Execute |
| 'RUNNING' | Core executando | GL e swap somente pelo caminho de comandos do wrapper |
| 'QUIESCING' | STOP enviado ou Execute retornando | Drenar comandos; rejeitar novos comandos de jogo |
| 'PLUGIN_STOPPED' | Core/plugin encerrados com comandos GL drenados | Destruir surface/context |
| 'EGL_RELEASED' | EGL destruído e janela liberada | Voltar a 'NO_WINDOW' |

Transições inválidas devem falhar de forma explícita; não podem tentar “corrigir” o estado chamando EGL de outra thread.

### Ordem de propriedade e sincronização

1. **Thread proprietária:** escolher uma única thread estável para o EGL. Na arquitetura threaded do GLideN64, a candidata é a thread de execução de comandos do wrapper, mas isso precisa ser comprovado no ponto exato em que 'setThreadedMode(1)' inicia a thread e em que o video extension chama 'SetMode'.
2. **Inicialização:** reter a janela Android; iniciar/ativar o caminho de comandos; criar EGL e chamar 'eglMakeCurrent' na thread que será proprietária; somente então chamar 'PluginStartup'. Não chamar 'PluginStartup' na UI nem na antiga thread do 'GLSurfaceView'.
3. **Comandos:** nenhuma chamada GLES direta pode escapar do wrapper. A criação, troca, destruição de surface e swap devem obedecer ao mesmo proprietário EGL. Não pode existir um segundo caminho que chame 'eglMakeCurrent' concorrentemente.
4. **Janela:** adquirir uma referência de 'ANativeWindow' antes de publicar a janela ao worker. Ao substituir ou destruir a janela, serializar a troca na thread EGL e liberar a referência antiga somente depois que nenhuma operação puder usá-la.
5. **Locks:** nunca manter o mutex de estado/janela enquanto aguarda sincronamente um comando do wrapper. O callback pode precisar do mesmo mutex no worker; esperar segurando esse lock cria ciclo clássico de deadlock.
6. **Parada:** fechar admissão de novas operações; solicitar STOP; aguardar 'M64CMD_EXECUTE' retornar; drenar a fila GL e swaps pendentes; executar 'PluginShutdown' com EGL atual; destruir EGL; por último liberar 'ANativeWindow'.
7. **Perda de surface:** não destruir o contexto enquanto ainda há comandos ou swaps em andamento. Suspender a apresentação, drenar o que for seguro e recriar a EGLSurface na thread proprietária quando uma nova janela chegar. Se o contexto precisar ser recriado, reinicializar o plugin em sequência controlada.
8. **Timeout:** timeout de STOP, drain ou teardown não prova que a thread terminou. Preservar handles e bloquear uma nova geração do Core até uma recuperação explícita, seguindo a política já usada pelo lifecycle atual.

### Ponto de atenção: ciclo de vida do wrapper

Ainda falta provar, no código upstream efetivamente baixado pelo workflow, a ordem exata entre:
- criação da thread interna do wrapper;
- ativação de 'setThreadedMode(1)';
- chamadas de 'CoreVideo_Init' e 'CoreVideo_SetVideoMode';
- chamada Android de 'VidExtFuncSetMode';
- 'PluginStartup' e 'PluginShutdown';
- destruição/join da thread do wrapper.

Essa ordem é decisiva. Se a thread do wrapper só nascer durante 'PluginStartup', não podemos pressupor que ela já esteja disponível para criar o EGL antes do startup. Se 'SetMode' for chamado sincronicamente durante startup e despachado para a thread recém-criada, o bridge precisa responder sem depender de um recurso que só será criado depois do próprio callback. Esse possível ciclo de inicialização deve ser resolvido no desenho antes de escrever o código.

### Plano de prova de conceito em etapas pequenas

**POC 0 — observabilidade, sem mudar renderização**
- Logar TID em 'PluginStartup', 'PluginShutdown', 'VidExtFuncSetMode', swap, 'eglMakeCurrent' e no loop do wrapper.
- Registrar identidade de EGLContext/EGLSurface e transições de surface.
- Confirmar a ordem real dos callbacks na build Android.

**POC 1 — bridge EGL isolado**
- Implementar um componente nativo independente do Core que receba uma janela, crie EGL numa thread dedicada e desenhe apenas um frame de teste.
- Validar criação, resize, pause, destruição e recriação de janela no Poco C85.
- Ainda sem carregar GLideN64 e sem ativar threaded mode.

**POC 2 — integração de vídeo**
- Integrar os callbacks 'VidExt_*' ao bridge, mantendo o modo threaded desligado inicialmente.
- Provar que todos os callbacks e swaps usam o mesmo TID/contexto.
- Validar shutdown e troca de surface sem ROM.

**POC 3 — wrapper threaded**
- Ativar 'setThreadedMode(1)' somente depois de comprovar que a thread de comandos é a proprietária EGL e que o startup não tem ciclo de dependência.
- Adicionar limite de fila/backpressure e métricas de comandos/swap.
- Testar GoldenEye e comparar várias execuções com a build atual.

**POC 4 — decisão**
- Só considerar a migração válida se estabilidade, áudio, imagem e tempos de frame forem pelo menos iguais e houver melhoria repetível nos engasgos.
- Manter a implementação antiga como fallback até a decisão final.

**Próxima ação técnica definida:** executar a POC 0. Ela reduz a incerteza de inicialização sem mexer no caminho de renderização que já funciona. Nenhuma mudança de código de runtime ou APK threaded foi feita nesta atualização.
