# Mupen64Plus Core — contrato da API e plano de integração Android

## Fonte auditada

- Repositório: https://github.com/mupen64plus/mupen64plus-core
- Commit: `a9ca8ec0b7decd17390c76c6341cf423d5d79321`
- Arquivos examinados: `src/api/m64p_frontend.h`, `src/api/m64p_types.h`, `src/api/m64p_plugin.h`, `src/api/m64p_vidext.h`, `src/api/frontend.c`, `src/main/main.c`, `projects/unix/Makefile` e `LICENSES`.

## Descoberta principal: o frontend controla o Core por uma API C

O header `m64p_frontend.h` define os ponteiros de função que o frontend deve resolver na biblioteca do Core. A integração Android não deve chamar funções internas aleatórias do motor: deve começar pela API pública e validar o retorno de cada chamada.

Funções centrais identificadas:
- `CoreStartup(...)`: inicializa o Core e recebe caminhos de configuração/dados e callbacks.
- `CoreShutdown()`: encerra o Core e libera seus recursos.
- `CoreAttachPlugin(type, handle)` / `CoreDetachPlugin(type)`: conecta e desconecta plugins.
- `CoreDoCommand(command, int, void*)`: interface de comandos para abrir/fechar ROM, executar, parar, pausar, retomar e outras operações.
- `CoreOverrideVidExt(...)`: permite fornecer funções de extensão de vídeo próprias em vez de depender automaticamente das funções SDL internas.

Os tipos e códigos de erro vêm de `m64p_types.h`. A interface deve preservar os tipos C exatos, convenção de chamada e valores enum da versão fixada. Não recriar esses enums “de memória”.

## Sequência de ciclo de vida a implementar

1. Carregar a biblioteca nativa do Core e resolver os símbolos públicos necessários.
2. Consultar versão/capacidades e validar compatibilidade da API.
3. Preparar diretórios privados de configuração, dados, saves e cache no armazenamento do app.
4. Chamar `CoreStartup` uma única vez e verificar `m64p_error`.
5. Inicializar cada plugin escolhido com `PluginStartup`, passando o handle do Core e callback de log thread-safe.
6. Quando o usuário selecionar uma ROM, validar o arquivo e enviar o comando de abertura pela API.
7. Anexar os plugins na ordem exigida pela documentação: **Video, Audio, Input, RSP**. O Core e os plugins precisam estar inicializados; o estado da ROM deve respeitar os requisitos da API.
8. Enviar o comando de execução. Executar/pausar/parar fora da thread principal da interface.
9. Ao encerrar: parar a emulação, destacar plugins, fechar a ROM e chamar `PluginShutdown` para cada plugin.
10. Chamar `CoreShutdown` somente depois de liberar os recursos dependentes.

A sequência final será codificada contra os requisitos exatos do commit fixado e validada com retornos reais. Não assumir que abrir a ROM e anexar plugins pode ocorrer em qualquer ordem.

## Contratos dos plugins

A documentação de plugin confirma que o frontend usa principalmente `PluginGetVersion`, `PluginStartup` e `PluginShutdown`. As funções específicas de vídeo, áudio, entrada e RSP são invocadas pelo Core.

A documentação da API também exige compatibilidade da versão principal entre plugin e Core. Portanto:
- não carregar plugin sem consultar tipo e versão da API;
- não tratar um plugin como biblioteca intercambiável só porque exporta símbolos com nomes parecidos;
- anexar um plugin por tipo;
- manter os plugins isolados até cada licença e ABI serem auditadas.

## Decisão para Android

O frontend Android será Kotlin + JNI. A camada JNI será pequena e terá responsabilidades claras:
- expor operações de inicialização, seleção/abertura de ROM, iniciar, pausar, retomar, parar e encerrar;
- converter erros C em mensagens compreensíveis;
- encaminhar logs para Kotlin sem acessar UI diretamente a partir de threads nativas;
- manter sincronização e impedir chamadas de ciclo de vida concorrentes.

A renderização exigirá integração explícita da extensão de vídeo com uma superfície Android/EGL. `CoreOverrideVidExt` é um ponto de extensão a investigar; isso não prova, por si só, que o plugin gráfico atual funcione com SurfaceView/TextureView. O backend gráfico será selecionado e auditado separadamente.

## Bloqueios de portabilidade encontrados

- `src/main/main.c` inclui `SDL.h`; há dependência de SDL no caminho de compilação atual.
- O Makefile Unix detecta SO/CPU do ambiente de build e configura Linux/macOS/BSD/Windows. Isso não é uma configuração Android NDK pronta.
- O Core contém código de plataforma, carregamento dinâmico, threads, caminhos de arquivo e partes de vídeo que precisam ser isolados ou adaptados para Android.
- ARM64 é reconhecido no Makefile como AArch64, mas isso não demonstra que todas as dependências, assembly/dynarec ou bibliotecas estejam prontas para Android.

Por isso, o próximo passo de implementação é criar uma camada Android/JNI mínima e um plano de build NDK reproduzível, antes de importar todo o Core. Não declarar uma build Android funcional até o CI produzir uma biblioteca ARM64 válida.

## Próximas tarefas

- [ ] Criar estrutura Gradle Android mínima com Kotlin.
- [ ] Criar módulo nativo CMake/NDK e bridge JNI sem funções falsas de emulação.
- [ ] Definir diretórios privados e callback de log thread-safe.
- [ ] Mapear dependências SDL e decidir substituição/portabilidade.
- [ ] Integrar o Core fixado, preservando licenças e proveniência.
- [ ] Só então selecionar e integrar plugins de vídeo, áudio, entrada e RSP.
