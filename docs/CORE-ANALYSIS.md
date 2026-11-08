# Mupen64Plus Core — análise inicial

## Fonte fixada para a primeira auditoria

- Projeto: https://github.com/mupen64plus/mupen64plus-core
- Branch upstream consultada: `master`
- Commit fixado para análise: `a9ca8ec0b7decd17390c76c6341cf423d5d79321`
- Data do commit: 2026-11-06
- Licença declarada em `LICENSES`: GNU GPL versão 2.

Não acompanhar `master` automaticamente. Atualizações do upstream só entram após revisão e atualização explícita deste registro.

## O que o Core fornece

O README do upstream esclarece que o repositório contém somente a biblioteca Core. Para um emulador funcional, também são necessários:
- plugin gráfico;
- plugin de áudio;
- plugin de entrada;
- plugin RSP;
- frontend/interface do usuário.

Isso confirma a arquitetura que adotaremos: o Core é o motor, enquanto o app Android será responsável por carregar ROMs, configurar plugins, controlar o ciclo de vida, fornecer superfícies de renderização e apresentar controles.

## Mapa inicial do código-fonte

Diretórios observados em `src/` no commit fixado:

| Diretório | Área a investigar |
|---|---|
| `api/` | contratos públicos e ABI do Core |
| `asm_defines/` | definições usadas pelo código assembly |
| `backends/` | backends de plataforma e serviços |
| `debugger/` | suporte de depuração |
| `device/` | dispositivos e periféricos do console |
| `main/` | inicialização e coordenação do Core |
| `osal/` | abstração de sistema operacional |
| `osd/` | exibição de informações na tela |
| `plugin/` | carregamento e comunicação com plugins |

Essas descrições são um roteiro inicial baseado na estrutura dos diretórios; cada arquivo será analisado antes de qualquer adaptação.

## Requisitos de integração Android

O README upstream lista opções de compilação como `USE_GLES=1`, `NEON=1`, `NO_ASM=1` e `DEBUG=1`. Elas são pistas para a investigação, não prova de que a compilação Android atual funcione sem alterações.

Plano de portabilidade:
1. Identificar dependências de sistema e chamadas POSIX/SDL.
2. Separar o que é essencial ao Core do que pertence ao frontend desktop.
3. Verificar arquitetura ARM64 e opções de assembly/dynarec.
4. Preparar compilação NDK/CMake ou adaptar o build upstream com o mínimo de mudanças.
5. Exportar a API nativa por JNI sem bloquear a thread de UI.
6. Validar carregamento do Core antes de integrar vídeo e áudio.

## Resultado da análise da API pública

A primeira leitura dos headers e do código confirmou que o frontend deve controlar o motor pela API C pública (`CoreStartup`, `CoreShutdown`, `CoreAttachPlugin`, `CoreDetachPlugin`, `CoreDoCommand` e `CoreOverrideVidExt`). Os tipos, enums e erros precisam vir dos headers da mesma versão fixada. A sequência de inicialização, os requisitos de ROM/plugins e os bloqueios de portabilidade estão registrados em [docs/API-PORTING.md](API-PORTING.md).

O código de entrada do Core (`src/main/main.c`) inclui SDL, enquanto o Makefile Unix configura plataformas desktop e não constitui um build NDK Android pronto. Portanto, ainda não afirmamos que o Core compile para Android.

## Ordem de engenharia reversa

1. API pública e ciclo de vida: startup, attach plugins, ROM open/close e shutdown.
2. Contratos/ABI dos plugins: GFX, AUDIO, INPUT e RSP.
3. Inicialização de ROM e tratamento dos formatos .z64, .v64 e .n64.
4. Renderização: contexto EGL/GLES e thread responsável.
5. Áudio: callbacks, buffers e sincronização.
6. Entrada: mapeamento de controles e polling.
7. Save RAM, estados e persistência.

## Regras de segurança técnica

- Não copiar todo o frontend Mupen64Plus AE sem auditar a licença própria dele.
- Não misturar plugins GPL-3.0 em uma distribuição GPL-2.0.
- Preservar arquivos de licença, créditos e cabeçalhos.
- Manter o upstream como submódulo ou referência com commit imutável.
- Cada adaptação terá patch identificável e documentação.
