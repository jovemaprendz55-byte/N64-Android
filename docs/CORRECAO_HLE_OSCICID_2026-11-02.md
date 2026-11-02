# Correção do parâmetro HLE osCicId — 2026-11-02

## Objetivo preservado

O emulador deve iniciar jogos por HLE, sem depender de PIF ROM, IPL3 original ou firmware extraído de um Nintendo 64. O código original do IPL3 foi consultado apenas como referência para reproduzir o estado visível entregue ao jogo.

## Evidência técnica

A área de parâmetros de boot da libultra começa em RDRAM virtual `0x80000300` (física `0x300`). O campo `osCicId` está em `0x80000310`, separado do valor de seed entregue em `$s6`.

As referências consultadas indicam:
- `0x80000300`: `osTvType` (0 PAL, 1 NTSC, 2 MPAL).
- `0x80000304`: `osRomType` (0 cartucho, 1 64DD).
- `0x80000308`: `osRomBase` (0xB0000000 cartucho, 0xA6000000 64DD).
- `0x8000030C`: `osResetType` (0 cold, 1 NMI).
- `0x80000310`: `osCicId`.
- `0x80000314`: `osVersion`.
- `0x80000318`: `osMemSize`.

Para as variantes relevantes:
- CIC 6102/7101: o IPL3 não escreve um identificador não-zero em `osCicId`; o campo permanece zero no estado de boot frio.
- CIC 6105/7105: `osCicId` recebe o valor decimal `6105`.
- As seeds continuam separadas: `0x3F` para CIC 6102/7101 e `0x91` para CIC 6105/7105. A seed é passada em `$s6`; não deve ser copiada para `osCicId`.

Fontes:
- ReverseEngineering.wiki, Platform:Nintendo 64, tabela de low-memory boot flags: https://reverseengineering.wiki/index.php/Platform%3ANintendo_64
- en64 wiki, N64 Memory, tabela de `osCicId` e comportamento por CIC: https://en64.shoutwiki.com/wiki/N64_Memory
- n64dev, `src/boot/pif.S`, atribuição do CIC ID em `osCicId`: https://github.com/mikeryan/n64dev/blob/master/src/boot/pif.S
- Ultra64 Functions Reference, osGlobals: https://ultra64.ca/files/documentation/online-manuals/functions_reference_manual_2.0i/os/osGlobals.html

## Alteração realizada

Na branch `dev/n64-clean-start`:
- `N64HleBoot.kt` agora declara `BOOT_FLAG_CIC_ID = 0x310`.
- O HLE converte a seed detectada em identificador de boot:
  - seed `0x3F` -> `osCicId = 0`;
  - seed `0x91` -> `osCicId = 6105`.
- O valor é escrito explicitamente em `0x310`, inclusive zero para CIC 6102, evitando herdar conteúdo residual caso o HLE seja executado sobre uma RDRAM que não esteja zerada.
- `$s6` continua recebendo a seed original, sem alteração.

Commits:
- Implementação: `85a80b63af2651615fba78041d69ff63624a21f2`.
- Testes: `ea655678cf489c22cce0033697694a05ba5afe7a` (HEAD observado no momento desta auditoria).

## Testes adicionados

`N64HleBootTest` agora:
- Pré-carrega valor residual em `0x310` e confirma que CIC 6102 o zera.
- Cria IPL3 sintético cujo somatório identifica CIC 6105/7105.
- Confirma seed `0x91` em `$s6`, identificador decimal `6105` em `0x310`, e que os dois valores são diferentes.
- Mantém o teste sem PIF ROM.

## Estado de validação

No momento da consulta, GitHub Actions executava o workflow Android build:
- Run: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41423118943
- Estado observado: `in_progress`.
- O endpoint de combined commit status retornou lista vazia; isso não equivale a CI aprovado.

Nenhum APK foi declarado pronto ou testado no aparelho nesta etapa. A alteração cobre somente o parâmetro `osCicId`; não comprova ainda boot completo, inicialização real do jogo, execução RSP/OSTask ou apresentação de vídeo.

## Próximas etapas

1. Aguardar o workflow Android build e inspecionar os logs se falhar.
2. Se passar, baixar/verificar o artefato APK antes de o oferecer para teste.
3. Continuar o HLE independente de firmware: revisar os restantes parâmetros de boot, estado inicial COP0/BEV/TLB, cache e inicialização de RDRAM, distinguindo comportamento necessário do IPL3 de comportamento que pode ser reproduzido diretamente pelo HLE.
4. Não executar nem distribuir IPL3/PIF ROM; usar documentação, código-fonte de referência e testes próprios.
5. Voltar ao caminho de execução do jogo e rastrear o primeiro ponto de falha real, sem inventar OSTask nem forçar SP HALT.

