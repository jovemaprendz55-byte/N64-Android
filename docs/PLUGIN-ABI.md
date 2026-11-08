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
