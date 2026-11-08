# Contratos ABI dos plugins Mupen64Plus

## Fonte e licença

- Repositório: https://github.com/mupen64plus/mupen64plus-core
- Commit auditado: `a9ca8ec0b7decd17390c76c6341cf423d5d79321`
- Headers: `src/api/m64p_plugin.h`, `src/api/m64p_types.h`, `src/api/m64p_common.h`
- Licença upstream declarada: GPL-2.0 (verificar o cabeçalho e LICENSES de cada arquivo antes de incorporar cópias).

Esta página registra análise dos contratos; ainda não copia headers nem implementa plugins.

## Tipos e ordem dos plugins

O enum `m64p_plugin_type` em `m64p_types.h` define valores estáveis nesta versão:
- `M64PLUGIN_NULL = 0`
- `M64PLUGIN_RSP = 1`
- `M64PLUGIN_GFX = 2`
- `M64PLUGIN_AUDIO = 3`
- `M64PLUGIN_INPUT = 4`
- `M64PLUGIN_CORE = 5`

Não substituir esses valores por uma enumeração própria presumida. A API de attach recebe `m64p_plugin_type` e um `m64p_dynlib_handle`.

## Contratos de inicialização

Os headers declaram os seguintes pontos de entrada comuns dos módulos:
- `PluginGetVersion(...)`: identifica tipo, versão e compatibilidade do módulo.
- `PluginStartup(handle, context, callback)`: inicializa o plugin e liga-o ao Core.
- `PluginShutdown()`: encerra e libera recursos do plugin.
- `RomOpen()` / `RomClosed()`: ciclo de vida do plugin em relação à ROM.

O frontend não deve chamar funções de plugin sem validar o tipo/versão e a ordem de inicialização. O ciclo exato deve ser confirmado no código do loader do Core antes da implementação.

## Dados compartilhados por interface

- GFX recebe `GFX_INFO`: ponteiros para RDRAM/DMEM/IMEM, registradores MI/DPC/VI e callbacks do Core. A estrutura inclui campo `version`; alguns campos só podem ser lidos a partir das versões indicadas nos comentários do header.
- AUDIO recebe `AUDIO_INFO`: memória e registradores AI, além de callback para interrupções.
- INPUT recebe `CONTROL_INFO`, que aponta para quatro estruturas `CONTROL`; os estados dos botões são representados por `BUTTONS`.
- RSP recebe `RSP_INFO`: memórias, registradores SP/DPC e callbacks para listas de display/áudio/RDP.

Essas estruturas contêm ponteiros para memória e registradores internos do Core. Portanto, não podem ser reconstruídas em Kotlin nem serializadas como mensagens JNI; a fronteira deve permanecer em C/C++ com headers da mesma revisão.

## Decisões para Android

1. A primeira meta continua sendo compilar o Core ARM64; plugin real ainda não está integrado.
2. A interface JNI será um frontend Android, não um substituto para a ABI binária dos plugins.
3. O plugin gráfico exige uma estratégia de superfície/contexto GLES e sincronização de thread. Não presumir que um plugin desktop OpenGL funciona em Android.
4. Áudio e input serão tratados depois que o carregamento do Core e o contrato de plugin estiverem validados.
5. Não carregar plugins desktop arbitrários do armazenamento do usuário; módulos precisam ser construídos para ABI Android e empacotados/validados pelo app.

## Próximos passos verificáveis

- Auditar em `src/plugin/plugin.c` a sequência real de load, version check, startup, attach, ROM open/close e shutdown.
- Identificar se o Core exige que plugins sejam carregados por nome/caminho via `osal_dynlib` e quais ajustes Android são necessários.
- Auditar a implementação do backend de vídeo e o contrato de `CoreOverrideVidExt` antes de escrever o plugin GFX.
- Depois dessas verificações, importar os headers necessários com seus avisos de licença e configurar o primeiro alvo de compilação NDK.

## Limite atual

Este documento não significa que plugins estejam implementados, que o Core esteja compilado, ou que alguma ROM esteja executando.

## Auditoria do loader e caminho de vídeo (revisão fixada)

Auditoria adicional em `src/plugin/plugin.c`, `src/api/m64p_frontend.h`, `src/api/m64p_plugin.h`, `src/api/m64p_vidext.h` e `src/api/m64p_types.h`, todos na revisão `a9ca8ec0b7decd17390c76c6341cf423d5d79321`.

### O que o loader confirma

- O Core mantém implementações dummy internas para GFX, áudio, input e RSP. Isso permite inicializar o Core sem plugins externos, mas não fornece renderização real de jogos.
- O plugin GFX real precisa exportar, no mínimo, `PluginGetVersion`, `ChangeWindow`, `InitiateGFX`, `MoveScreen`, `ProcessDList`, `ProcessRDPList`, `RomClosed`, `RomOpen`, `ShowCFB`, `UpdateScreen`, `ViStatusChanged`, `ViWidthChanged`, `ReadScreen2`, `SetRenderingCallback`, `FBRead`, `FBWrite` e `FBGetFrameBufferInfo`. `ResizeVideoOutput` é opcional.
- O loader verifica que o tipo retornado seja `M64PLUGIN_GFX` e que a versão principal da API GFX corresponda à esperada pelo Core. Não basta exportar funções com os nomes corretos.
- A estrutura `GFX_INFO` é preenchida pelo próprio Core em `plugin_start_gfx`; seus campos contêm ponteiros para RDRAM, DMEM/IMEM e registradores internos. Portanto, um plugin gráfico deve ser nativo C/C++ e usar exatamente os headers da revisão fixada.
- Áudio, input e RSP também possuem listas obrigatórias de símbolos e validação de tipo/API. O Core tem dummies para esses módulos, mas o uso de dummies não deve ser confundido com áudio ou controles Android funcionais.

### Consequência para Android

- Não tentar abrir uma ROM só porque `CoreStartup` funcionou. A ROM precisa de um caminho gráfico compatível; o plugin gráfico é a primeira integração real necessária para exibir imagem.
- O frontend pode fornecer uma implementação própria de extensão de vídeo por `CoreOverrideVidExt`, em vez de depender das funções de janela SDL/OpenGL desktop. O contrato inclui inicialização/encerramento de vídeo, criação de modo, atributos e troca de buffers; a superfície Android e o contexto GLES precisam ser controlados pela Activity/View e pela thread de renderização.
- A ordem segura a implementar é: manter Core inicializado; preparar superfície/contexto Android; registrar callbacks de extensão de vídeo; carregar e validar o plugin GFX Android; anexá-lo ao Core; só então abrir ROM e executar. Em falha, desfazer em ordem inversa antes de descarregar bibliotecas.

### Comandos de ROM identificados

Em `m64p_types.h`, os comandos são enumeração sequencial: `M64CMD_ROM_OPEN = 1`, `M64CMD_ROM_CLOSE = 2` e `M64CMD_EXECUTE = 5`. A API de frontend declara `CoreDoCommand(m64p_command, int, void*)`. Estes números foram confirmados no header, mas o payload/tamanho exato de ROM_OPEN e o tratamento de ciclo de vida ainda devem ser conferidos no código de implementação antes de qualquer chamada JNI. Não usar valores numéricos sem os headers oficiais.

### Próximo marco de implementação

1. Auditar o corpo de `CoreOverrideVidExt` e as chamadas feitas pelo backend SDL do Core.
2. Definir uma View Android com superfície GLES e ciclo de vida explícito, sem criar contexto OpenGL em thread errada.
3. Construir primeiro um plugin GFX mínimo de diagnóstico para Android, validando ABI e callbacks sem abrir ROM.
4. Só depois integrar seleção de arquivo ROM, validar cabeçalho/formato e chamar `M64CMD_ROM_OPEN` com o payload oficial confirmado.

**Estado:** análise de ABI/loader documentada; nenhum plugin gráfico Android foi implementado nesta etapa e nenhuma ROM foi aberta.

## Auditoria das funções de vídeo e abertura de ROM

A revisão do código de `src/api/vidext.c` confirma que `CoreOverrideVidExt` encaminha para `OverrideVideoFunctions`. Quando o override está ativo, o Core encaminha as operações `VidExt_Init`, `VidExt_InitWithRenderMode`, `VidExt_Quit`, `VidExt_SetVideoMode`, resize, caption, fullscreen, atributos OpenGL e swap buffers para os callbacks do frontend. A implementação Android deve fornecer uma tabela `m64p_video_extension_functions` coerente com o header; uma tabela incompleta pode desativar o override e voltar ao backend SDL padrão. Não registrar ponteiros JNI/Kotlin como se fossem callbacks C sem uma camada nativa estável.

### Payload exato de ROM_OPEN

A implementação em `src/api/frontend.c` remove a ambiguidade identificada acima:

- `CoreDoCommand(M64CMD_ROM_OPEN, ParamInt, ParamPtr)` recebe `ParamPtr` apontando para os bytes da ROM em memória e `ParamInt` como tamanho em bytes.
- O Core rejeita ponteiro nulo, tamanho inferior a 4096 bytes ou superior a `CART_ROM_MAX_SIZE`; o comentário do Core exige que o tamanho seja divisível por 4 para evitar leitura fora dos limites ao trocar bytes em formatos V64/N64.
- `M64CMD_ROM_CLOSE` requer ROM aberta e emulador parado.
- `M64CMD_EXECUTE` é comando separado; abrir a ROM não inicia o jogo por si só.
- `CoreAttachPlugin` só aceita anexar plugin quando o Core está inicializado e já existe ROM ou disco aberto; por isso o plano anterior de anexar GFX antes de abrir ROM precisa ser ajustado. O plugin GFX precisa estar pronto para o ciclo de abertura/execução de ROM conforme a sequência real do Core, e o frontend deve tratar falha de abertura/attach sem deixar estado parcialmente inicializado.

### Ordem revisada para implementação

1. Terminar e testar a ponte de vídeo Android (`CoreOverrideVidExt` + tabela de callbacks) antes de abrir ROM.
2. Preparar o plugin GFX Android e validar todos os símbolos obrigatórios, tipo e API version.
3. Auditar a sequência interna de `open_rom`/plugin start para definir precisamente quando anexar o plugin GFX e quando iniciar a superfície GLES.
4. Ler a ROM escolhida via Storage Access Framework, validar magic/ordem de bytes/tamanho e manter buffer nativo válido durante o uso.
5. Chamar `M64CMD_ROM_OPEN` com buffer e tamanho confirmados; só depois solicitar `M64CMD_EXECUTE`. Fechar com `M64CMD_ROM_CLOSE` antes de desligar plugins/Core.

**Correção de planejamento:** não anexar plugin via `CoreAttachPlugin` antes de abrir ROM; a API explicitamente retorna estado inválido nesse cenário. Ainda não foi implementada abertura de ROM nem renderização.

## Resultado do teste ABI no dispositivo Android (2026-11-08)

O usuário executou no Poco C85 o botão 7 após carregar e inicializar o Core. Resultado informado:

```
ABI de video validada: 17 callbacks aceitos pelo Core.
Tabela de teste desativada imediatamente.
Nenhuma janela, plugin GFX ou ROM foi iniciada.
```

A auditoria do header upstream `src/api/m64p_vidext.h` na revisão fixada confirmou os tipos das 17 funções/campos da tabela. A implementação em `src/api/vidext.c` confirma que `Functions >= 17` é obrigatório e que qualquer ponteiro nulo limpa a tabela externa e desativa o override retornando `M64ERR_SUCCESS`; portanto, a segunda chamada com a tabela zerada é o mecanismo correto para remover o probe.

**Interpretação:** o Core aceitou a tabela ABI de diagnóstico em execução no aparelho e o probe foi desativado. Isso não valida ainda callbacks Android funcionais, criação de contexto GLES pelo Core, plugin GFX, renderização ou execução de ROM. O próximo marco é implementar callbacks reais ligados à superfície/contexto Android, respeitando a thread proprietária do contexto e o ciclo de vida da Activity; manter a abertura de ROM bloqueada até o caminho gráfico real estar pronto.

## Auditoria final da ordem de inicialização GFX e thread Android (2026-11-08)

Fonte upstream revisada: `src/api/frontend.c`, `src/main/rom.c` e `src/plugin/plugin.c`, no mesmo commit fixado.

### Ordem real confirmada

1. `CoreStartup` define `l_CoreInit=1`; só depois disso `CoreOverrideVidExt` deixa de retornar `M64ERR_NOT_INIT`. O botão 7 já validou esse momento.
2. `CoreDoCommand(M64CMD_ROM_OPEN, tamanho, buffer)` valida e copia a ROM para a memória interna, mas **não inicia o plugin GFX**.
3. Depois de ROM aberta, `CoreAttachPlugin(M64PLUGIN_GFX, handle)` conecta o módulo e chama `plugin_start(M64PLUGIN_GFX)`.
4. `plugin_start_gfx()` preenche `GFX_INFO` com os ponteiros do Core e chama `InitiateGFX(GFX_INFO)`. Essa é a chamada que inicia o caminho gráfico do plugin.
5. `CoreDoCommand(M64CMD_EXECUTE)` chama `main_run()` e bloqueia até o jogo terminar/parar. Não chamar essa função na thread da interface Android.

### Consequência para a superfície GLES

- A superfície e o contexto GLES precisam existir antes do attach/início do plugin GFX.
- A tabela de callbacks de vídeo deve estar registrada após `CoreStartup` e antes de `InitiateGFX`.
- Os callbacks que usam GLES só podem operar enquanto o contexto correto estiver atual e na thread proprietária dele. Não basta que uma `GLSurfaceView` exista na tela.
- A integração deve coordenar o ciclo de vida `onPause/onResume/onDestroy`, perda/recriação do contexto EGL e parada do Core antes de destruir a superfície.
- A chamada bloqueante `M64CMD_EXECUTE` precisa rodar fora da thread UI; a estratégia de execução deve preservar a propriedade do contexto EGL. Não implementar uma chamada JNI direta na UI nem presumir que `runOnUiThread` resolve a afinidade de contexto.

**Decisão de implementação:** antes de abrir ROM, criar uma ponte Android que prove a posse/validade do contexto EGL na thread que executará as chamadas nativas. Em seguida implementar callbacks reais e um plugin GFX Android mínimo; só então liberar a sequência ROM_OPEN → CoreAttachPlugin(GFX) → EXECUTE. Até lá, o app permanece em modo diagnóstico sem jogos.


## Resultado do teste EGL no Poco C85 (2026-11-08)

O usuário instalou a compilação de diagnóstico e executou o botão 6. Resultado informado:

```
OpenGL ES 2.0 inicializado.
Superficie grafica Android criada.

Contexto EGL valido na thread GL. OpenGL ES: OpenGL ES 3.2 V1.r49p1-03bet0.19498e0ae1d5dac2233 83c39a2e58f04
Thread Linux: 31878
Contexto: 0xB400007468B8AD00 | Surface: 0xB400007468B8C410

Nenhuma ROM foi carregada.
```

**Interpretação confirmada no aparelho:** dentro de `GLSurfaceView.Renderer.onSurfaceCreated`, a JNI conseguiu consultar um contexto EGL atual, uma superfície de desenho atual e a string de versão GLES. O driver reportou OpenGL ES 3.2, embora a View tenha solicitado contexto versão 2; isso é compatível com a solicitação mínima de GLES 2.0 e não significa que o frontend já use recursos GLES 3.x.

**Limite do teste:** a evidência prova que o contexto EGL está atual na thread GL durante a chamada JNI de diagnóstico. Ainda não prova que chamadas do Core/plugin GFX ocorrerão nessa mesma thread, que o contexto sobreviverá a pause/resume, ou que existe renderização N64.

### Próxima etapa segura

Antes de implementar um plugin GFX, criar um teste isolado de passagem de chamada nativa pela thread GL: a View deve enviar uma tarefa para a GL thread, a JNI deve validar contexto/surface e registrar o TID da chamada; a resposta deve voltar à UI sem executar operações GLES na UI. Depois, desenhar a estratégia de ciclo de vida e de execução do Core. Manter ROM_OPEN e EXECUTE bloqueados até que a ponte de thread e os callbacks reais estejam validados.


## Resultado do teste JNI despachado pela thread GL no Poco C85 (2026-11-08)

O usuário instalou a compilação do commit `2ca87e1506b03bdf019133a320d7e34477ffbc64` e executou o botão 8. Resultado informado:

```
Ponte JNI Via GLSurfaceView.queueEvent
Contexto EGL valido na thread GL. OpenGL ES: OpenGL ES 3.2 v1.r49p1-03bet0.19498e0ae1d5dac2233 83c39a2e58f04
Thread Linux: 6890
Contexto: 0xB400007468B98E90 | Surface: 0xB400007468B92D40
Nenhuma ROM foi carregada.
```

**Interpretação:** a tarefa enviada por `GLSurfaceView.queueEvent` executou a JNI em uma thread com contexto EGL atual e superfície de desenho válida. Isso confirma a ponte de despacho para a thread GL neste teste real no aparelho. O TID `6890` é o identificador observado nessa execução; ele pode mudar entre execuções e não deve ser fixado como constante.

**Limites:** a validação ainda não chama funções do Core Mupen64Plus nem callbacks de plugin GFX, não testa pause/resume ou perda/recriação do contexto e não renderiza ROM. O próximo trabalho é implementar e testar o ciclo de vida da ponte nativa e callbacks de vídeo mínimos na thread GL, mantendo ROM_OPEN e EXECUTE bloqueados até que esses testes estejam concluídos.


## Resultado do teste 9 — operações GLES pela JNI no Poco C85 (2026-11-08)

O usuário instalou a compilação do commit `c6d0990549221c7dbde05a4d5bec48ae4763e45f` e executou o botão 9. Resultado informado:

```
Teste 9 - Operacoes GLES na thread GL
Operacoes GLES executadas pela JNI.
OpenGL ES: OpenGL ES 3.2 v1.r49p1-03bet0.19498e0ae1d5dac2233c39a2e58f04
Viewport: 680 x 360 (origem 0,0)
Erro GLES antes: 0x0000 | depois: 0x0000
Thread Linux: 12399
Contexto EGL valido; nenhuma ROM/plugin foi iniciada.

Nenhuma ROM foi carregada.
```

**Interpretação:** na thread GL, a JNI consultou o viewport e executou `glClearColor`, `glClear(GL_COLOR_BUFFER_BIT)`, `glFlush` e `glGetError`. O viewport reportado foi 680 × 360, origem (0,0), e não houve erro GLES nem antes nem depois das operações. O contexto EGL permaneceu válido durante a chamada. O TID 12399 é apenas o identificador observado nessa execução.

**O que foi validado:** operações OpenGL ES reais podem ser chamadas pela ponte JNI enquanto a tarefa é despachada pela `GLSurfaceView.queueEvent` para a thread que possui o contexto EGL.

**Limites:** isto ainda não valida callbacks de vídeo do Mupen64Plus, integração do Core com a GL thread, plugin GFX, ciclo de vida pause/resume, framebuffer do emulador, abertura de ROM ou execução de jogo. Nenhuma ROM/plugin foi iniciada.

### Próximo marco

Projetar um teste visual nativo isolado na GL thread (sem Core, plugin ou ROM), com recursos GLES mínimos e descarte correto ao perder/recriar o contexto. Em paralelo, auditar a estrutura de callbacks de vídeo Android e o ciclo de vida da superfície antes de conectá-los ao Core. Não liberar ROM_OPEN nem EXECUTE até que o caminho de vídeo real esteja implementado e validado.


## Resultado do teste 10 — desenho OpenGL na GL thread no Poco C85 (2026-11-08)

O usuário instalou a compilação do commit `59d362ff0578a869b45e981e9af1c94bdd767c9c` e executou o botão 10. Resultado informado:

```
Programa GLES compilado. Triangulo colorido sendo desenhado.

Contexto EGL valido na thread GL. OpenGL ES: OpenGL ES 3.2
Thread Linux: 28257
Contexto EGL e Surface validos.
Nenhuma ROM ou plugin foi iniciado.
```

**Interpretação:** os shaders GLES foram compilados e vinculados com sucesso, e o renderer executou o desenho de um triângulo com cores interpoladas na superfície Android. A validação EGL ocorreu na thread GL e confirmou contexto e superfície atuais. O TID é apenas o valor observado nessa execução.

**O que foi validado:** o aplicativo consegue compilar shaders GLES 2.0 e emitir uma chamada real de desenho (`glDrawArrays(GL_TRIANGLES)`) no renderer da `GLSurfaceView`, sem iniciar o Core, plugin GFX ou ROM.

**Limites:** o triângulo é um teste gráfico próprio do frontend Android, não usa o Mupen64Plus nem dados de jogo. Ainda não comprova callbacks gráficos do Core, framebuffer N64, textura, sincronização com o emulador, ciclo de vida pause/resume ou renderização de ROM.

### Próxima etapa segura

Antes de ligar o Core à superfície, implementar testes de ciclo de vida do renderer: pausa e retomada da Activity, destruição/recriação da superfície e recriação dos recursos GLES no novo contexto. Garantir que shaders/programas antigos não sejam reutilizados após perda do contexto. Depois, projetar a ponte de callbacks de vídeo para a thread GL. Manter ROM_OPEN, CoreAttachPlugin(GFX) e EXECUTE bloqueados até que o ciclo de vida e os callbacks reais estejam validados.


## Organização da interface de diagnóstico (2026-11-08)

A tela inicial permanece simples, com o acesso "Diagnóstico e testes". Dentro do diagnóstico, os testes foram separados em três grupos recolhíveis:
- Biblioteca e JNI: carregar a biblioteca e testar JNI/SDL2.
- Core Mupen64Plus: carregar, inicializar/encerrar o Core e verificar ABI de vídeo.
- Gráficos e OpenGL: superfície GLES, desenho do triângulo, ponte JNI na thread GL e operações GLES pela JNI.

Os grupos começam recolhidos para reduzir a ocupação vertical. A tela de diagnóstico usa rolagem para aparelhos menores. O teste OpenGL é inserido dentro do grupo Gráficos e OpenGL. Esta alteração reorganiza somente a interface; não muda a integração do Core, não carrega ROM e não habilita renderização de jogos.


## Resultado do teste 11 — ciclo de vida EGL no Poco C85 (2026-11-08)

O usuário instalou a compilação do commit `2fdf275451f7cbde52164e4d454c75d12ab426dc` e executou o teste 11 de pausa/retomada. Resultado informado:

```
Programa GLES compilado. Triangulo colorido sendo desenhado.
Superficie EGL criada: 2 vez(es).
Contexto EGL valido na thread GL. OpenGL ES: OpenGL ES 3.2
Thread Linux: 6046
Contexto e Surface EGL validos.
Nenhuma ROM ou plugin foi iniciado.
```

**Interpretação:** o callback `onSurfaceCreated` ocorreu duas vezes durante o teste. Na verificação posterior, a JNI encontrou contexto EGL, superfície de desenho e versão GLES válidos na thread GL. O programa GLES do triângulo foi compilado no callback de criação da superfície.

**O que foi validado:** o frontend conseguiu passar pelo ciclo de pausa/retomada observado, receber duas criações de superfície e voltar a executar o teste gráfico com contexto EGL válido.

**Limites:** o contador não prova, sozinho, que o contexto EGL foi destruído entre as duas criações; para afirmar perda/recriação do contexto é necessário comparar identificadores antes e depois e registrar `onSurfaceCreated` e `onSurfaceChanged` em cada ciclo. Também não valida callbacks do Core, plugin GFX, framebuffer N64 ou execução de ROM.

**Próxima etapa:** instrumentar o ciclo EGL com registro dos identificadores de contexto/surface e estado de compilação dos recursos em cada `onSurfaceCreated`. Em seguida, construir a ponte de vídeo Android com callbacks reais, garantindo que operações GL ocorram exclusivamente na thread que possui o contexto. Manter ROM_OPEN, CoreAttachPlugin(GFX) e EXECUTE bloqueados.


## Primeiro modulo do plugin GFX Android (2026-11-08)

Foi criado `app/src/main/cpp/android_gfx_plugin.cpp` e o alvo CMake
`n64android_gfx`, empacotado como `libmupen64plus-video-android.so`.
O modulo exporta `PluginGetVersion`, `PluginStartup`, `PluginShutdown`,
`InitiateGFX`, `RomOpen`, `RomClosed` e os callbacks de vídeo/framebuffer
exigidos pelo ABI legado do plugin GFX. `InitiateGFX` guarda o `GFX_INFO`
fornecido pelo Core e recusa inicialização sem contexto EGL/GLES atual.

**Limite funcional importante:** este é o primeiro esqueleto ABI compilável,
não um renderer N64. `ProcessDList` e `ProcessRDPList` ainda não interpretam
comandos RSP/RDP, não há rasterização, texturas, framebuffer N64 nem imagem de
jogo. O plugin ainda não é carregado/associado via `CoreAttachPlugin`, e ROM
continua desabilitada. O próximo passo é adicionar carregamento dinâmico e
validar `PluginGetVersion`/símbolos do módulo antes de conectar sua inicialização
ao Core; só depois implementar o interpretador gráfico real.

## Carregamento dinâmico do plugin GFX no fluxo Android (2026-11-08)

O commit `e29a483bc271f6af7323640dd8b61fddeebfc0ea` conecta o carregador
JNI do plugin à etapa "Iniciar integração gráfica". Depois de registrar a
Video Extension Android com sucesso em `GLSurfaceView.Renderer.onSurfaceCreated`,
o frontend monta o caminho de
`libmupen64plus-video-android.so` em `applicationInfo.nativeLibraryDir`
e chama `nativeLoadGfxPlugin(path)` na thread GL, com contexto EGL atual.

A JNI abre o módulo com `dlopen(RTLD_NOW | RTLD_LOCAL)`, resolve os 19
símbolos GFX obrigatórios, consulta `PluginGetVersion` e valida tipo
`M64PLUGIN_GFX` e major da API 2. O handle permanece guardado para a etapa
seguinte. A compilação Android Actions run `42486286940` (run 119) terminou
`completed / success`, job `assemble`.

**Limite desta etapa:** carregar e validar a biblioteca não chama
`PluginStartup`, não executa `CoreAttachPlugin`, não abre ROM e não executa
`CoreDoCommand(M64CMD_EXECUTE)`. Portanto, ainda não há plugin GFX conectado
ao Core nem renderização de jogos. O módulo atual continua sendo um esqueleto:
`ProcessDList` e `ProcessRDPList` não interpretam comandos RSP/RDP nem
produzem framebuffer N64.

**Próxima sequência técnica:** confirmar no código-fonte do Core pinned a
ordem e responsabilidade de `PluginStartup`, `M64CMD_ROM_OPEN` e
`CoreAttachPlugin(M64PLUGIN_GFX)`; só então implementar a inicialização e
associação com tratamento de erro e encerramento seguro. Manter a abertura de
ROM e execução bloqueadas até existir um renderer que realmente interprete
listas RSP/RDP e respeite a thread proprietária do contexto EGL.


## Validacao real do carregamento e inicializacao do plugin GFX (2026-11-08)

O usuario instalou a compilacao 124 no Poco C85 (Android 16) e executou
"Iniciar integracao grafica". O resultado exibido no aparelho foi:

```
Integracao grafica Android registrada no Core.
Contexto EGL valido na thread GL. OpenGL ES: OpenGL ES 3.2
Video Extension Android registrada no Core.
Callbacks EGL/GLES ativos na thread GL.
Plugin GFX Android carregado e validado.
Nome: N64 Android GLES Graphics Plugin (initial ABI)
Versao: 0.1.0 | API: 0x020000
Simbolos obrigatorios: 19/19.
PluginStartup executado. Ainda nao anexado ao Core; nenhuma ROM foi aberta.
```

**Resultado confirmado em aparelho:** o modulo foi aberto por `dlopen`, os 19
simbolos exigidos foram encontrados, `PluginGetVersion` passou pela validacao
de tipo/API e `PluginStartup` retornou sucesso. O aplicativo permaneceu aberto.
A compilacao 124 (run `42488246030`) terminou com sucesso.

A compilacao 125 (run `42488890198`, commit
`505ef76748c5a4cb47a6cea42b590ad91f595b13`) corrigiu a mensagem de estado
na interface para distinguir plugin inicializado de plugin anexado. O workflow
`assemble` terminou com sucesso e gerou o artefato
`n64-android-core-isolated-diagnostic` (artifact ID `13621106119`).
Essa compilacao nao altera a logica de inicializacao do plugin.

### Responsabilidades de inicializacao confirmadas no Core pinned

A revisao de `src/plugin/plugin.c` e `src/api/frontend.c` confirmou:

- O frontend chama `PluginStartup(coreHandle, ...)` uma vez para iniciar
  a biblioteca carregada; o resultado deve ser acompanhado por
  `PluginShutdown()` antes de descarregar o modulo.
- `CoreAttachPlugin(M64PLUGIN_GFX, pluginHandle)` conecta os simbolos do
  plugin e valida sua ABI; depois chama `plugin_start_gfx()`.
- `plugin_start_gfx()` monta `GFX_INFO` com os ponteiros internos do Core e
  chama `InitiateGFX(GFX_INFO)`. Portanto, nao se deve chamar
  `InitiateGFX` manualmente no frontend nem repetir `PluginStartup` durante
  o attach.
- `CoreAttachPlugin` so aceita o attach quando uma ROM ou disco ja esta
  aberto e o emulador nao esta em execucao.

### Limite atual e proximo marco

O teste acima **nao** anexou o plugin ao Core, nao chamou `InitiateGFX`,
nao abriu ROM e nao executou o emulador. O modulo atual ainda e apenas um
esqueleto ABI: `ProcessDList` e `ProcessRDPList` nao interpretam comandos
RSP/RDP nem geram framebuffer de jogo.

Antes de liberar ROM, a proxima etapa grafica e implementar um caminho de
renderizacao real: interpretar comandos de display list/RDP, converter estado
e primitivas N64 em chamadas GLES, e apresentar o framebuffer na superficie
Android. Em paralelo, a integracao precisa garantir que chamadas GLES do
plugin ocorram na thread que possui o contexto EGL. Abertura de ROM,
`CoreAttachPlugin` e `M64CMD_EXECUTE` permanecem bloqueados ate esses
requisitos estarem implementados e validados.


## Auditoria de renderer existente para Android ARM64 (2026-11-08)

Foi feita uma comparacao inicial de implementacoes existentes antes de escrever
um rasterizador RDP proprio ou copiar codigo sem verificar a licenca.

### Candidato principal: GLideN64

- Repositorio: https://github.com/gonetz/GLideN64
- Revisao consultada: `a04081ad6a966a9413bea63a55f502e0dc98bb18`
- O arquivo `LICENSE` declara GNU GPL versao 2.
- O CMake do projeto oferece explicitamente `ANDROID=ON`, seleciona
  `MUPENPLUSAPI=ON` para a ABI Mupen64Plus, adiciona fontes EGL/Android e
  produz o modulo `libmupen64plus-video-GLideN64.so`.
- A documentacao de build informa suporte ao alvo Android e otimizações ARM
  NEON/ARMv8. O plugin implementa processamento de display lists, RSP/RDP,
  framebuffer e renderizacao OpenGL.

**Conclusao:** e o candidato mais promissor para adaptar, por combinar GPL-2.0,
ABI Mupen64Plus e caminho Android declarado pelo proprio projeto. Isso ainda
nao significa que compila neste app nem que funciona com a superficie atual.

### Alternativa: Mupen64Plus Video-Rice

- Repositorio: https://github.com/mupen64plus/mupen64plus-video-rice
- O arquivo `LICENSES` declara GNU GPL versao 2.
- O historico/documentacao upstream registra suporte OpenGL ES 2.0.
- E um plugin HLE mais antigo e pode servir como alternativa caso a integracao
  do GLideN64 exija uma adaptacao excessiva.

### Pontos que precisam ser resolvidos antes da integracao

1. O GLideN64 usa a Video Extension do Core (`CoreVideo_Init`,
   `CoreVideo_SetVideoMode`, `CoreVideo_GL_SwapBuffers`). A extensao Android
   existente precisa fornecer esses callbacks usando a superficie EGL que o
   `GLSurfaceView` ja possui, sem criar uma segunda janela ou contexto.
2. O codigo Mupen64Plus do GLideN64 solicita perfil OpenGL core 3.3. O aparelho
   reportou OpenGL ES 3.2; e necessario verificar as chamadas de atributo e o
   backend GLES do plugin, sem presumir compatibilidade entre desktop GL 3.3 e
   OpenGL ES 3.2.
3. O plugin tem modo de renderizacao em thread separada. Para a primeira
   integracao, desativar esse modo e garantir que callbacks de desenho e troca
   de buffers sejam executados na thread proprietaria do EGL context.
4. Verificar todos os subcomponentes/licencas, fontes Android, dependencias,
   simbolos ABI, leitura/escrita de framebuffer e encerramento de contexto.
5. Fazer primeiro uma compilacao ARM64 isolada no CI e validar os simbolos do
   modulo. So depois integrar o plugin ao carregador JNI e testar a inicializacao
   sem abrir ROM. A abertura de ROM e a execucao continuam bloqueadas ate a
   validacao do ciclo grafico real.

### Decisao desta auditoria

Nao foi copiado nem integrado codigo de renderer nesta etapa. O proximo trabalho
sera uma compilacao isolada e uma adaptacao de GLideN64 para reutilizar o EGL e a
Video Extension Android existentes. Se o requisito de OpenGL desktop 3.3 nao
puder ser atendido pelo backend Android/GLES, interromper essa via e avaliar o
Video-Rice antes de alterar o ciclo do Core.


### Resultado da compilacao isolada do GLideN64 (2026-11-08)

A compilacao ARM64 isolada do GLideN64 terminou com sucesso no workflow
129 (run `42489620919`, commit `9860264e4f1878bcf2228ff02c0bc53810c48ca7`).
A configuracao `CRC_ARMV8=OFF` evitou a falha anterior de intrinseco CRC
ARMv8 nao habilitado no alvo; o build selecionou `CRC_OPT_NEON.cpp`.

Validacoes observadas no log do CI:

- O alvo `libmupen64plus-video-GLideN64` chegou a 100% e foi linkado.
- O arquivo produzido e ELF compartilhado ARM64/AArch64 para Android API 23,
  compilado com NDK r27.
- Os simbolos dinamicos `PluginGetVersion`, `PluginStartup`,
  `InitiateGFX`, `ProcessDList` e `ProcessRDPList` foram verificados.
- O APK gerado nesse workflow continua empacotando apenas
  `libmupen64plus-video-android.so` (plugin GLES inicial proprio). O GLideN64
  ainda **nao** foi copiado para o APK, carregado pelo JNI ou anexado ao Core.

**Proximo marco:** estudar e adaptar a integracao Video Extension do GLideN64
para a superficie EGL/GLSurfaceView existente. Antes de adicionar o .so ao APK,
resolver a compatibilidade do backend grafico com OpenGL ES 3.2 e a afinidade de
thread do contexto EGL. Nenhuma ROM deve ser aberta nesta fase.


### Auditoria complementar do backend GLideN64 versus EGL/GLES (2026-11-08)

A leitura do codigo upstream fixado em `a04081ad6a966a9413bea63a55f502e0dc98bb18`
refinou a avaliacao de compatibilidade:

- `opengl_GLInfo.cpp` identifica explicitamente drivers cujo `GL_VERSION`
  contem `OpenGL ES`, diferencia GLES2 de GLES3+ e possui caminhos de recursos
  para GLES. Portanto, nao e correto concluir que GLideN64 exige exclusivamente
  desktop OpenGL 3.3.
- O backend Mupen64Plus `mupen64plus_DisplayWindow.cpp` chama
  `CoreVideo_GL_SetAttribute` solicitando perfil CORE, major 3, minor 3,
  double-buffer e outros atributos antes de `CoreVideo_Init/SetVideoMode`.
  Esse pedido e um ponto de adaptacao: a extensao Android nao deve tentar criar
  outro contexto nem exigir desktop GL 3.3; deve mapear/ignorar atributos
  incompatíveis e reutilizar o EGL context ES 3.2 ja corrente.
- `_start()` executa `CoreVideo_Init`, configura atributos, chama
  `CoreVideo_SetVideoMode` e depois `_swapBuffers()` usa
  `CoreVideo_GL_SwapBuffers`. Na integracao Android, esses callbacks precisam
  ser implementados como ponte para a superficie existente, sem trocar o
  contexto nem fazer swap duplicado fora do GLSurfaceView.
- A configuracao `ThreadedVideo` e encaminhada para
  `FunctionWrapper::setThreadedMode`; para a primeira integracao sera
  obrigatorio desativar renderizacao em thread separada e manter chamadas GL
  na thread dona do EGL context.

**Decisao tecnica atualizada:** manter GLideN64 como candidato principal. O
proximo passo de codigo e auditar a Video Extension do Core e a implementacao
Android atual para mapear os callbacks `CoreVideo_*` a EGL/GLSurfaceView,
começando por atributos e modo de video. Ainda nao integrar o .so nem habilitar
ROM; primeiro produzir uma camada de compatibilidade sem criar contexto ou
surface próprios e compilar/testar o carregamento sem executar emulacao.


## Auditoria da ponte Android já existente (2026-11-08)

Arquivos do frontend revisados em `app/src/main/cpp/native-lib.cpp` e `app/src/main/java/com/jsr/n64android/MainActivity.kt`.

### Fluxo atualmente implementado

- A Activity cria uma `GLSurfaceView` e, dentro de `Renderer.onSurfaceCreated`, chama `nativeVerifyEglContext()`.
- No mesmo callback da GL thread, quando o fluxo de integração gráfica é selecionado, chama `nativeRegisterAndroidVideo()` e, após registrar a tabela, chama `nativeLoadGfxPlugin()`. Portanto, o registro e o carregamento inicial do módulo acontecem no callback de criação da superfície, não na thread UI.
- `nativeRegisterAndroidVideo()` exige Core inicializado e contexto EGL/GLES atual, preenche os 17 callbacks e registra a tabela com `CoreOverrideVidExt`.
- `androidVideoInit()`, `androidVideoSetMode()`, `androidVideoGetProc()`, `androidVideoSwapBuffers()` e `androidVideoDefaultFramebuffer()` verificam a existência do contexto atual antes de prosseguir quando aplicável.
- `androidVideoSetMode()` atualmente só valida dimensões e guarda largura/altura. `androidVideoSetAttr()` apenas guarda valores em uma tabela; ainda não interpreta nem valida os atributos solicitados pelo renderer.
- `androidVideoSwapBuffers()` não chama EGL swap, corretamente evitando uma segunda troca de buffers fora do controle do `GLSurfaceView`.
- `androidVideoDefaultFramebuffer()` consulta `GL_FRAMEBUFFER_BINDING` no contexto corrente.
- `androidVideoGetProc()` tenta `eglGetProcAddress` e depois `dlsym(RTLD_DEFAULT, ...)`.

### Lacunas antes de considerar a ponte pronta para GLideN64

1. O callback de atributo precisa de uma política explícita para atributos compatíveis com EGL/GLES e atributos desktop que não podem ser aplicados ao contexto já criado. Retornar sucesso e apenas guardar qualquer valor pode fazer o plugin acreditar que recebeu um perfil/contexto que Android não oferece.
2. O callback de modo de vídeo precisa garantir que dimensões e viewport estejam sincronizados com a superfície real. Não deve criar janela, EGL context, EGL surface nem chamar `eglMakeCurrent`.
3. O callback de swap precisa continuar sem executar `eglSwapBuffers`; a apresentação deve permanecer sob o ciclo de renderização do `GLSurfaceView`. É necessário confirmar como o renderer GLideN64 sinaliza o fim de frame e como o frontend solicita/agenda o próximo frame.
4. Todos os callbacks que consultam GLES devem ser executados na GL thread que possui o contexto. A checagem de contexto atual é uma proteção, não um mecanismo de despacho entre threads.
5. O ciclo `onPause/onResume` pode destruir/recriar o contexto. A tabela de callbacks pode permanecer registrada, mas nenhum recurso GL do plugin poderá ser presumido válido após recriação; o plugin precisará receber o ciclo de vida correto antes de qualquer ROM.

### Decisão

A ponte existente é uma base real e já foi registrada no Core no aparelho, mas ainda é uma camada de validação/diagnóstico, não uma implementação completa de janela/vídeo para GLideN64. Próxima tarefa de código: mapear os valores de `m64p_GLattr` do header fixado e as solicitações efetivas do GLideN64, então implementar validação explícita dos atributos e sincronização de viewport/superfície, sem alterar a propriedade do EGL. Manter ROM_OPEN, CoreAttachPlugin e EXECUTE bloqueados até esse trabalho e a integração do renderer serem validados.


## Auditoria dos atributos GL solicitados pelo GLideN64 (2026-11-08)

Fontes fixadas e comparadas:
- Core: `src/api/m64p_types.h`, commit `a9ca8ec0b7decd17390c76c6341cf423d5d79321`.
- GLideN64: `src/Graphics/OpenGLContext/mupen64plus/mupen64plus_DisplayWindow.cpp`, commit `a04081ad6a966a9413bea63a55f502e0dc98bb18`.

### Enum oficial confirmado

O enum `m64p_GLattr` do Core define:
- 1 `M64P_GL_DOUBLEBUFFER`
- 2 `M64P_GL_BUFFER_SIZE`
- 3 `M64P_GL_DEPTH_SIZE`
- 4 `M64P_GL_RED_SIZE`
- 5 `M64P_GL_GREEN_SIZE`
- 6 `M64P_GL_BLUE_SIZE`
- 7 `M64P_GL_ALPHA_SIZE`
- 8 `M64P_GL_SWAP_CONTROL`
- 9 `M64P_GL_MULTISAMPLEBUFFERS`
- 10 `M64P_GL_MULTISAMPLESAMPLES`
- 11 `M64P_GL_CONTEXT_MAJOR_VERSION`
- 12 `M64P_GL_CONTEXT_MINOR_VERSION`
- 13 `M64P_GL_CONTEXT_PROFILE_MASK`

O enum de perfil define `M64P_GL_CONTEXT_PROFILE_CORE=0`, `COMPATIBILITY=1` e `ES=2`.

### Conflito concreto encontrado

No código upstream GLideN64, `DisplayWindowMupen64plus::_setAttributes()` solicita explicitamente:
- perfil `CORE`;
- OpenGL major 3, minor 3;
- double buffering;
- swap control configurado;
- buffer total 32 bits;
- depth 16 bits;
- multisampling opcional.

O Poco C85 fornece um contexto **OpenGL ES 3.2**, não um contexto desktop OpenGL 3.3. O callback Android atual apenas armazena os atributos e retorna sucesso; isso mascara o conflito e pode fazer o renderer acreditar que recebeu um perfil desktop que não existe.

### Decisão técnica

Não aceitar silenciosamente o perfil CORE nem declarar que o contexto é OpenGL 3.3. A integração Android precisa de uma adaptação pequena e explícita no backend GLideN64:
1. Solicitar `M64P_GL_CONTEXT_PROFILE_ES` em vez de `CORE`.
2. Solicitar versão mínima GLES compatível com o backend GLES selecionado, sem exceder a capacidade observada do aparelho.
3. Tratar buffer/depth/multisample como propriedades do contexto/surface já criado: validar o que foi realmente fornecido, sem tentar recriar EGL nem prometer valores não garantidos.
4. Tratar swap control como no-op gerenciado pelo Android/GLSurfaceView.
5. Consultar atributos de volta a partir das capacidades reais do contexto quando o plugin precisar deles.

A adaptação precisava ser feita no backend GLideN64 upstream, e não apenas no callback do frontend. Essa adaptação agora existe como patch separado e rastreável em `patches/gliden64/android-gles-profile.patch`; o CI aplica a alteração com uma verificação estrita do bloco original e compila o plugin ARM64.

### Estado histórico da auditoria inicial

Quando esta auditoria foi escrita, o patch ainda não existia e o plugin real ainda não estava empacotado. Esse estado foi superado pela seção **GLideN64 real compilado e empacotado no Android** abaixo. A etapa atual comprova build/ABI/empacotamento; ainda falta o teste de `PluginStartup` no Poco C85. Nenhuma ROM foi aberta.


## GLideN64 real compilado e empacotado no Android (2026-11-08)

O CI passou a compilar o plugin upstream GLideN64 na revisão fixada `a04081ad6a966a9413bea63a55f502e0dc98bb18`, com licença GPL-2.0 preservada na árvore upstream e uma adaptação local pequena em `patches/gliden64/android-gles-profile.patch`.

### Adaptação de contexto

O backend upstream solicitava OpenGL desktop 3.3 CORE. O Poco C85 reporta OpenGL ES 3.2. O patch solicita perfil ES e versão mínima 3.0. A ponte Android também passou a rejeitar perfil desktop e versões acima do contexto GLES atual, em vez de aceitar atributos que não correspondem ao EGL real.

### Evidência CI

- Commit: `2542d80a348db6e04918cb2c5427cc414d5e0d47`
- Workflow: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42504515807
- Resultado: success.
- `libmupen64plus-video-GLideN64.so` foi compilada como ELF AArch64 para Android 23 e incluída no APK.
- O CI verificou os exports obrigatórios do plugin GFX e as dependências ELF do sistema Android.
- O frontend Android agora aponta para esse plugin real quando chama `nativeLoadGfxPlugin`.

### Limite desta etapa

O CI só prova compilação, exports e empacotamento. O botão **Iniciar integração gráfica** no aparelho será o primeiro teste de `PluginStartup` do GLideN64 real. Ainda não chamamos `InitiateGFX`, não anexamos o plugin com `CoreAttachPlugin`, não abrimos ROM e não executamos jogo.

### Bloqueio de thread/contexto

O GLideN64 Android ativa `threadedVideo` por padrão e sua camada `FunctionWrapper` pode executar chamadas GL em uma thread de comandos própria. O EGL atualmente validado pertence à thread GL do `GLSurfaceView`. Antes de `InitiateGFX`, precisamos definir como o contexto será tornado atual na thread que efetivamente executará as chamadas GL e como o loop bloqueante `M64CMD_EXECUTE` coexistirá com a troca de buffers do Android. Não desativar threadedVideo nem mover o contexto sem um teste explícito de afinidade.

### Próximo passo

Instalar o APK do CI no Poco C85 e verificar se o carregamento do plugin real e `PluginStartup` terminam com sucesso. Depois desenhar/testar a ponte de contexto e thread; só então liberar o início gráfico e o ciclo ROM_OPEN → attach GFX → EXECUTE.

## Afinidade da thread GL no GLideN64 Android (2026-11-08)

Auditoria da revisao upstream fixada a04081ad6a966a9413bea63a55f502e0dc98bb18:

- src/Config.cpp define video.threadedVideo = 1 para OS_ANDROID.
- mupen64plus_DisplayWindow.cpp::_start() passa esse valor a
  FunctionWrapper::setThreadedMode.
- opengl_Wrapper.cpp confirma que o modo threaded cria
  m_commandExecutionThread; as funcoes wr* enviam comandos a essa
  thread e aguardam a execucao.
- O contexto EGL do frontend pertence a thread GL do GLSurfaceView.
  Portanto, o modo threaded upstream pode executar chamadas GLES numa thread
  que nao possui esse contexto.

### Alteracao pequena e verificavel

O patch local patches/gliden64/android-gles-profile.patch agora força
FunctionWrapper::setThreadedMode(0) apenas sob OS_ANDROID. Nas demais
plataformas, o valor configurado continua sendo respeitado. O workflow aplica
a alteracao somente se a chamada upstream esperada aparecer exatamente uma
vez e verifica o resultado antes de compilar o plugin ARM64.

### Limites e proxima validacao

Isso remove a thread GL privada do GLideN64, mas nao implementa por si so o
despacho do Core para a thread do GLSurfaceView. O frontend ainda precisa
executar InitiateGFX, callbacks de frame e qualquer chamada GLES na thread
que possui o EGL atual, inclusive apos onPause/onResume recriar o contexto.
O proximo marco continua sendo validar PluginStartup e, depois, desenhar
uma chamada controlada de inicializacao grafica na GL thread. ROM_OPEN,
CoreAttachPlugin e EXECUTE permanecem bloqueados.

## Marco seguinte — validar PluginStartup na thread EGL

O frontend chama nativeLoadGfxPlugin a partir de GLSurfaceView.Renderer.onSurfaceCreated.
O JNI agora verifica EGLContext, EGLDisplay, EGL_DRAW surface e glGetString(GL_VERSION)
imediatamente antes de PluginStartup. Se qualquer elemento estiver ausente, o plugin
e descarregado sem chamar PluginStartup. Em sucesso, o log Android registra GLES, TID
e o identificador do EGLContext usados nessa chamada.

A interface também deixou de afirmar incondicionalmente que PluginStartup foi concluido;
o resultado exibido deve ser lido da mensagem retornada pelo carregamento do plugin.
Este teste prova a posse do contexto no instante de entrada de PluginStartup, mas nao
prova que o GLideN64 criou recursos, recebeu GFX_INFO via InitiateGFX, ou desenhou frames.
ROM_OPEN, CoreAttachPlugin e EXECUTE continuam bloqueados.
