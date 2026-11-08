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
