# Dependências Android — versões fixadas para a primeira integração

## Objetivo

Definir uma base reproduzível para compilar o Mupen64Plus Core oficial
`a9ca8ec0b7decd17390c76c6341cf423d5d79321` no Android ARM64. Estes pins
são uma seleção de dependências, ainda não uma integração compilada. O Core
continua sem target CMake e sem biblioteca `libmupen64plus.so`.

## Pins selecionados

| Componente | Versão | Repositório | Commit/tag imutável | Licença observada |
|---|---:|---|---|---|
| SDL2 | 2.32.10 | https://github.com/libsdl-org/SDL | `5d249570393f7a37e037abf22cd6012a4cc56a71` (`release-2.32.10`) | zlib-style SDL license |
| libpng | 1.6.59 | https://github.com/pnggroup/libpng | `cd952f49f95bb27154ae77dbb103032d95f6e580` (`v1.6.59`) | PNG Reference Library License v2 |
| zlib | 1.4 | https://github.com/madler/zlib | `dd9d5c2d62b52e8acb0e678de5768087eedce914` (`v1.4`) | zlib License |

Os commits acima foram resolvidos a partir das referências oficiais dos
repositórios. Para libpng, a tag assinada `v1.6.59` foi verificada como válida
e aponta para o commit listado. Preservar os arquivos de licença e avisos
originais de cada projeto ao importar ou empacotar os componentes.

## Decisões de compatibilidade

- O Core deste projeto usa a API SDL 2. Portanto, usar **SDL2**, não SDL3.
  SDL3 3.x é uma linha separada e não é substituta binária/fonte direta.
- SDL2 deve ser construído pelo projeto Android com NDK/CMake e empacotado
  conforme sua integração Android. A biblioteca compartilhada e o Java
  bootstrap/activity do SDL precisam ser tratados juntos; não basta incluir
  somente os headers.
- libpng depende de zlib. A build deve ligar os targets Android construídos
  com os mesmos ABI/API e toolchain, sem misturar bibliotecas host Linux.
- O NDK contém uma implementação de zlib compatível disponível para aplicações
  Android, mas a estratégia final será decidida depois de conferir o uso
  esperado pelo Core e o linking de libpng. Não ligar duas implementações
  concorrentes de zlib na mesma biblioteca.
- Minizip será inicialmente compilado a partir da cópia bundled no commit
  fixado do Core, mantendo seus avisos e licença; não ativar criptografia.
- O primeiro perfil continuará `arm64-v8a`, sem netplay e sem OSD/FreeType.
- A política Vulkan/GLES ainda precisa ser definida no target Core; não herdar
  silenciosamente os defaults do Makefile Unix.

## Próxima sequência verificável

1. Adicionar SDL2 como checkout fixado e compilar o target Android sozinho no
   CI, produzindo a biblioteca e empacotando o bootstrap necessário.
2. Confirmar se o Core realmente precisa de libpng no perfil selecionado;
   compilar libpng + zlib como targets Android somente se necessário.
3. Criar target CMake independente para o Core usando o manifesto explícito
   `cmake/Mupen64PlusCoreSources.cmake`, com flags Android derivadas do
   Makefile upstream e dos headers do commit fixado.
4. Compilar e linkar `libmupen64plus.so` no CI. Só depois começar integração
   JNI, plugins e execução de ROM.
5. Manter o build atual da bridge JNI e APK como teste de regressão.

## Fontes de verificação

- SDL2 releases: https://github.com/libsdl-org/SDL/releases
- SDL Android guide: https://github.com/libsdl-org/sdlwiki/blob/main/SDL2/Android.md
- libpng release e avisos de segurança: https://www.libpng.org/pub/png/libpng.html
- zlib release: https://www.zlib.net/

**Status:** pins registrados. O workflow agora faz checkout do SDL2 no commit
fixado e tenta compilar libSDL2.so para Android ARM64; aguardar o resultado
do Actions antes de considerar a biblioteca validada. libpng e zlib ainda não
foram importados ou compilados.
