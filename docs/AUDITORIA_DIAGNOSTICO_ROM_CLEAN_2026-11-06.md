# Auditoria do diagnóstico offline de ROM — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: leitura estática do fluxo de seleção, leitura e inspeção de ROM. Nenhum código de produção ou teste foi alterado.

## Arquivos examinados

- `app/src/clean/java/com/jsr/n64android/MainActivity.kt`
- `app/src/clean/java/com/jsr/n64android/N64RomDocumentReader.kt`
- `app/src/clean/java/com/jsr/n64android/N64HleCartridgeImage.kt`
- `app/src/clean/java/com/jsr/n64android/N64HleBootProfileFactory.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64RomDocumentReaderTest.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64HleCartridgeImageTest.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64HleBootProfileFactoryTest.kt`

## Capacidades já existentes na versão limpa

1. `MainActivity` seleciona arquivos via Android Storage Access Framework e lê em thread separada.
2. `N64RomDocumentReader` lê o stream com limite máximo de 64 MiB e retorna resultado tipado de sucesso/falha.
3. `N64HleCartridgeImage.parse()` reconhece os três byte orders comuns (Z64 big-endian, V64 byte-swapped e N64 little-endian 32-bit), normaliza para big-endian e valida magic, tamanho mínimo de 64 bytes e alinhamento de 32 bits.
4. O objeto normalizado já expõe tamanho, entry point, CRC1/CRC2 do cabeçalho, título, ID de cartucho e código de país.
5. A tela já mostra título, tamanho, formato e CRC1/CRC2 do cabeçalho; deixa claro que identificar a ROM não inicia CPU ou jogo.
6. O factory CIC 6102/7101 verifica soma do bloco IPL3 em 0x40..0xFFF e CRC1/CRC2 de 1 MiB a partir do offset 0x1000. Porém, essas rotinas são privadas e o factory rejeita a ROM via exceção quando CIC ou checksum não correspondem.

## Lacunas para um diagnóstico separado do boot

- A tela não apresenta entry point, game/cartridge ID nem país/região.
- Não apresenta a soma IPL3 calculada, o perfil CIC inferido ou a razão detalhada da não identificação do CIC.
- Não apresenta CRC1/CRC2 calculados lado a lado com os valores do cabeçalho antes de tentar preparar o handoff.
- O factory de boot combina diagnóstico e validação de perfil por meio de `require`; chamá-lo apenas para inspecionar não é adequado, pois falhas esperadas viram exceções e o resultado não é um relatório estruturado.
- `N64RomLoader.kt` e `N64RomInspector.kt` existem em `app/src/main`, mas não fazem parte do source set limpo. Não reutilizar nem copiar código da implementação antiga. A inspeção deve permanecer em `app/src/clean`.

## Desenho recomendado, ainda não implementado

Criar um inspetor puro e independente do boot, por exemplo `N64HleRomDiagnostics.kt`, que receba `N64HleCartridgeImage` e devolva um relatório imutável com:

- tamanho e byte order original;
- título, game/cartridge ID, country code e entry point;
- CRC1/CRC2 do cabeçalho;
- soma IPL3 observada e classificação CIC com nível de certeza explícito;
- CRC1/CRC2 calculados para CICs cujo algoritmo esteja implementado e validado, com comparação explícita contra o cabeçalho;
- estados distintos: válido, divergente, não suportado, dados insuficientes e erro de leitura.

Não inferir CIC só pelo nome/título/região. A soma atual de 6102/7101 pode ser reportada como assinatura reconhecida pelo código, mas não deve ser apresentada como detecção universal de CIC. Não calcular CRC 6102 se o bloco de 1 MiB não estiver disponível. Não confundir assinatura IPL3 com CRC1/CRC2.

A UI deve mostrar o relatório sem preparar handoff, sem iniciar CPU e sem alterar a ROM. O botão de preparar HLE continua sendo uma ação separada e explícita.

## Testes necessários antes de integração

- cabeçalho big-endian e normalização V64/N64;
- campos de título, ID, país e entry point;
- tamanho insuficiente para soma IPL3 e/ou bloco de 1 MiB;
- assinatura CIC 6102 conhecida e assinatura desconhecida;
- CRC1/CRC2 correspondentes, divergentes e caso de igualdade já coberto pelo teste de boot;
- distinção entre CIC não reconhecido, CRC divergente e perfil de boot não suportado;
- garantir que o diagnóstico nunca execute CPU, modifique bytes da ROM ou prepare HLE.

## Limites e próximo passo

Esta foi somente uma auditoria estática. Não houve execução de Gradle, CI ou teste no aparelho nesta etapa. Nenhuma ROM comercial foi aberta, solicitada ou compartilhada.

Antes de implementar o inspetor, é necessária autorização explícita para alterar arquivos de produção/teste. Até lá, manter este desenho como plano. O objetivo funcional permanece GoldenEye 007 NTSC-U, mas nem a aprovação do CI nem um diagnóstico de cabeçalho provam que o jogo executa.
