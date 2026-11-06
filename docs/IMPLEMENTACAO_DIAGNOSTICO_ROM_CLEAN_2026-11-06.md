# Implementação do diagnóstico offline de ROM — arquitetura limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Objetivo: diagnosticar ROMs sem iniciar CPU, preparar handoff ou modificar bytes da imagem.

## Implementado

- Criado `N64HleRomDiagnostics.kt` em `app/src/clean/java`.
- O relatório imutável expõe tamanho, byte order original, título, game/cartridge ID, país, entry point, CRC1/CRC2 do cabeçalho, soma IPL3, perfil CIC/seed e CRC1/CRC2 calculados quando há dados e algoritmo suportados.
- Estados explícitos: `VALID`, `DIVERGENT`, `UNSUPPORTED`, `INSUFFICIENT_DATA` e `READ_ERROR`.
- A identificação CIC não usa título ou região. A assinatura atualmente reconhecida é a soma IPL3 conservadora do perfil CIC 6102/7101 (seed 0x3F); isso não é uma detecção universal de CIC.
- CRC 6102 só é calculado quando o bloco de 1 MiB a partir de ROM offset 0x1000 está presente e a assinatura conhecida foi reconhecida.
- As rotinas de soma IPL3 e CRC 6102 são compartilhadas pelo diagnóstico e pelo factory de boot, evitando duplicação do algoritmo.
- Integrada a apresentação na tela inicial. O cálculo ocorre na thread de leitura da ROM; a tela recebe o relatório sem iniciar CPU, preparar HLE ou alterar a imagem.
- A ação PREPARAR HANDOFF HLE continua separada e explícita.

## Testes adicionados

Arquivo: `app/src/cleanTest/java/com/jsr/n64android/N64HleRomDiagnosticsTest.kt`.

- Cabeçalho, entry point, título, game/cartridge ID e país.
- Assinatura IPL3 CIC 6102/7101 reconhecida.
- CRC1/CRC2 correspondentes.
- CRC divergente do cabeçalho.
- Assinatura IPL3 desconhecida, sem inferência por título/região.
- Dados insuficientes para o bloco IPL3.
- Dados insuficientes para o bloco de jogo de 1 MiB após reconhecer IPL3.
- Verificação de que os bytes da ROM permanecem idênticos após o diagnóstico.

## Validação

- GitHub Actions workflow: [Android build](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42237127590).
- Commit testado: `8afbb8929061a953569bb192222b49b79b87a034`.
- Etapas `Build and test` e `Upload debug APK` concluídas com sucesso.
- A execução anterior de testes do núcleo diagnóstico também passou em [42237007266](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42237007266).

## Limites

- Não foi testado em aparelho Android nesta etapa.
- Não foi analisada nem compartilhada ROM comercial.
- Não houve boot de GoldenEye 007 NTSC-U nem execução de jogo.
- CIC 6105/7105 e outros perfis continuam não suportados por este diagnóstico.
- CI verde confirma compilação e testes automatizados, não compatibilidade funcional de jogo.

## Próxima etapa

Executar o APK no aparelho, selecionar a cópia legítima de ROM do usuário e conferir se os metadados/CRC locais são exibidos corretamente. Em seguida, voltar ao caminho de boot real do GoldenEye: validar o handoff, acompanhar a execução da CPU e priorizar TLB/MMIO/DMA/interrupções conforme a primeira falha observada. Não ampliar suporte CIC sem algoritmo e vetores independentes.
