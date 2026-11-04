# Limpeza de duplicações e código legado — 2026-11-04

Repositório: `jovemaprendz55-byte/N64-Android`  
Branch: `dev/n64-clean-start`  
Escopo: limpeza conservadora, sem alteração da branch `main`.

## Alterações aplicadas

### 1. Remoção do diagnóstico legado de IPL3

- Removida a função `runIpl3HandoffDiagnosticLegacy()` de `MainActivity.kt`.
- A função tinha 364 linhas de corpo e nenhuma chamada no arquivo: a única ocorrência do identificador era sua própria declaração.
- O caminho atual `runHleBootDiagnostic()` foi preservado.
- A remoção não modifica CPU, barramento, memória, estado COP0, TLB, SP, RSP, RDP ou o processo de boot HLE ativo.
- Commit: `d0997d797651908c659955348ee8bde06d2c5a19`.

### 2. Centralização da montagem do relatório de barramento

- Os blocos duplicados do caminho normal e do caminho de exceção foram substituídos pelo helper privado `appendBusDiagnostics(lines, bus, heading)`.
- Ambos os caminhos continuam exportando MMIO, snapshot dos dispositivos, DMA PI/SP, escritas RDRAM, acessos de boot, registradores SI e PIF RAM.
- O cabeçalho continua distinguindo execução normal de falha.
- Commit: `d4f88c4d9ffdb344a8dd72952c16e8755a287cac`.

## Duplicações analisadas e mantidas intencionalmente

### Rastreadores de instrução

- `N64HleInstructionTrace` é orientado a checkpoints HLE, contagem de PCs e resumo de PCs visitados.
- `N64InstructionTrace` mantém janela de instruções e detecção de loops repetidos usada pela sessão de ROM.
- Existem responsabilidades sobrepostas, mas não são substitutos diretos. Os dois têm testes próprios:
  - `N64HleInstructionTraceTest`
  - `N64InstructionTraceTest`
- Não unificar nesta limpeza: uma abstração comum agora poderia misturar requisitos de retenção, formato de snapshot e detecção de loop.

### Despacho RDP

- `execute(commands)` processa comandos single-word e mantém a implementação existente de fill.
- `executePackets(packets)` valida pacotes completos e acrescenta o caminho multiword de triângulos.
- O segundo usa o primeiro para comandos single-word. Isso é delegação/compatibilidade entre níveis, não duplicação integral.
- Testes dedicados existem em `N64RdpFillProcessorTest`. Não reescrever sem mapear todos os chamadores e expectativas dos testes.

### Sessões de execução e módulos SP/RSP

- `N64CpuExecutionSession` adapta o runner à interface comum de sessão.
- Bootstrap, runner, lifecycle, coordinator e microcode tracker SP têm responsabilidades distintas.
- `N64RspCore` e `N64RspVectorUnit` separam despacho/instruções da unidade vetorial.
- Não remover ou fundir esses componentes por semelhança de nomes.

## Verificações pendentes

- Os workflows Android build disparados pelos commits `d0997d7` e `d4f88c4` foram encontrados em execução na última consulta:
  - run `41799039823` para `d0997d7`;
  - run `41799094882` para `d4f88c4`.
- Aguardar conclusão e inspecionar os resultados antes de declarar a limpeza validada por CI.
- Ainda não foi executada uma análise AST/clone local de todos os 122 arquivos. Esta etapa foi uma auditoria estrutural/estática da árvore e dos módulos centrais, não uma prova de ausência de toda duplicação textual.
- Não foi feita limpeza adicional no RDP ou nos rastreadores porque a sobreposição observada é funcionalmente distinta e coberta por testes separados.

## Resultado

A limpeza removeu 366 linhas de código legado sem chamadas e substituiu dois blocos duplicados de relatório por um único helper. Nenhuma funcionalidade ativa do emulador foi intencionalmente removida. A validação final depende do resultado dos workflows citados.
