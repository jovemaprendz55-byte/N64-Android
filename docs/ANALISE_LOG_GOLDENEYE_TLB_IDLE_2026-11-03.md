# Análise do log GoldenEye 007 — TLB e loop idle

Data: 2026-11-03  
Branch de referência do código: `dev/n64-clean-start`  
Log analisado: `N64-Android-logs-1793697154759.txt` na branch `main`  
Escopo: análise estática do log exportado; não houve execução nova da ROM nem alteração funcional do emulador.

## Resultado confirmado no log

- ROM carregada: 12.582.912 bytes (12 MiB).
- HLE preparou 1 MiB.
- CPU: 7.968.975 instruções.
- RSP: 0 instruções.
- Frames VI publicados: 0.
- SP_STATUS: `0x1` (HALT).
- DPC START/END/CURRENT: todos zero.
- PC final: `0x70000720`; o padrão repetido alterna `0x7000071C` (`BEQ $zero,$zero,-1`) e `0x70000720` (`NOP`).
- Nenhuma escrita da CPU nos registradores SP/DPC foi registrada.

## Reinterpretação do TLB miss

O log registra um primeiro TLB miss de FETCH em VA `0x7F0BCC28`, com EntryHi `0x7F0BC000`. O snapshot capturado no instante da primeira falha ainda não contém uma entrada para esse par virtual.

Entretanto, o histórico posterior registra execução do vetor de refill e várias instruções `TLBWR`, incluindo:

```
TLBWR PC=0xFFFFFFFF80000034 index=2
EntryHi=0x7F0BC000
EntryLo0=0x0000DB9F
EntryLo1=0x0000DBDF
```

O snapshot final contém uma entrada para VPN2 `0x7F0BC000` no índice 2, além de outras páginas de `0x7F...`. Isso demonstra que o caminho de refill chegou a instalar uma tradução para a região solicitada. Portanto, o primeiro miss, isoladamente, não prova que a TLB seja a causa persistente do loop final. A implementação TLB ainda precisa de validação arquitetural mais ampla, mas não se deve desligá-la nem inserir mapeamentos artificiais com base neste log.

## O que o loop final significa

A instrução `0x1000FFFF` em `0x7000071C` é um branch incondicional para o próprio endereço; `0x00000000` no delay slot é NOP. É um padrão típico de espera/idle. O log mostra que a CPU chegou a esse loop após executar milhões de instruções e rotinas de inicialização, mas não demonstra por si só se a thread está esperando uma interrupção, uma fila, uma thread pronta ou uma inicialização que não terminou.

A contagem alta de leituras VI e os registradores VI programados mostram atividade de polling/configuração de vídeo, mas não provam que uma OSTask tenha sido criada ou submetida.

## Próximo bloqueio comprovado para o primeiro quadro

O bloqueio mais próximo do primeiro quadro é anterior ao RSP:

1. A CPU chega ao loop idle.
2. SP permanece em HALT.
3. Não há escrita CPU nos registradores SP.
4. RSP executa zero instruções.
5. DPC permanece sem comandos e VI não publica frames.

Assim, o próximo diagnóstico deve localizar a transição para o loop idle e identificar por que o fluxo guest não libera/agenda trabalho gráfico. A prioridade é correlacionar o estado do scheduler/thread do jogo, as interrupções efetivamente entregues e os writes SP/DPC; não integrar o executor OSTask por endereço presumido.

## Plano de investigação

1. Capturar a primeira entrada em `0x7000071C`, com PC anterior, instrução, GPRs relevantes, Status/Cause/EPC e estado MI.
2. Registrar separadamente interrupções VI latched, habilitadas, entregues à CPU e reconhecidas pelo guest.
3. Verificar se o idle loop é interrompido e para qual PC a CPU retorna.
4. Rastrear criação/ativação da thread responsável pelo vídeo e a primeira escrita em SP_STATUS/SP_MEM_ADDR/SP_DRAM_ADDR.
5. Só depois ligar o despachante de OSTask ao fluxo real observado.

## Limites

- Este log é uma execução antiga e não mede o código mais recente da branch `dev/n64-clean-start`.
- CI verde valida build/testes automatizados, não o boot de GoldenEye no Poco C85.
- Nenhuma causa raiz definitiva para a entrada no idle loop foi provada.
- Nenhuma alteração funcional foi feita nesta análise.
- A branch `main` permanece intocada.

## Instrumentação MI adicional — 2026-11-03

Após a aprovação da instrumentação inicial, foi encontrada e corrigida uma inconsistência: `R4300Cpu.reset()` limpava os demais históricos de diagnóstico, mas não limpava `diagnosticInterruptHistory`. O reset agora limpa esse histórico, com teste que primeiro entrega uma interrupção VI e depois verifica a limpeza.

Também foi criado `diagnosticMiInterruptSourceHistory` no `N64Bus`, limitado a 128 transições. Ele registra apenas mudanças reais de estado das fontes MI (ASSERT/ACK), sem registrar cada consulta da CPU. Foram instrumentados os caminhos VI, PI, DP e SP; o relatório exportado pelo aplicativo agora inclui esse histórico. O teste VI verifica tanto a asserção quanto o reconhecimento.

Commits desta continuação:
- `53469798ee3fda6dce1703ba9e48f095d209a76d` — limpar histórico MI no reset.
- `c3372a41bcf9f61724d69af0919b7f08877f8b7c` — teste de limpeza no reset.
- `49fc8cdcb247e9587a247ee2c298d9a8020dcc4b` — registrar transições de fontes MI.
- `a16b1f10fbf2c766ae69d6f958a4f03d2283a8de` — exportar histórico de fontes MI.
- `80f370e477ab4310a4a78fec9ccb1389569fb1a4` — validar ASSERT/ACK de VI.
- `d88717c4844351f44cc3094d3534d477af0ec94c` — cobrir transições PI/DP/SP.
- `e046bddf86321e0e4d99aa44f17cd7c602659b8e` — registrar a asserção SP explícita.

Próxima leitura do log novo:
1. Se houver `ASSERT source=VI` mas não `MI IRQ`, comparar máscara MI e Status/Cause da CPU.
2. Se houver `MI IRQ`, conferir EPC, vetor e retorno ERET.
3. Se houver `ACK source=VI`, verificar se o handler está reconhecendo a interrupção.
4. Se não houver ASSERT de nenhuma fonte, o problema está antes da entrega de IRQ; continuar seguindo o caminho de inicialização/idle sem presumir falha TLB.

**CI dos commits acima:** ainda precisa ser consultado após os pushes. Não há confirmação de execução real do GoldenEye no aparelho; é necessário gerar um log novo no Poco C85 com o APK desta revisão.

- `b522ce356adb34fae6601726e1d9fc44a8a248bc` — teste explícito de que `R4300Cpu.reset()` limpa o histórico de IRQ entregue.

- `96a1cf0bb4deffc6f9a921137512e377a729523b` — registrar alterações reais da máscara de interrupções MI (SET/CLEAR).
- `9065b19a0acb6902963947f36caa6f111981b87c` — teste para garantir que a máscara só registra transições reais.

## Teste adicional — fonte MI pendente com máscara desligada

Em 2026-11-03, o teste `maskedPiDmaInterruptDoesNotEnterCpuException` foi ampliado para validar a sequência completa:

1. Uma conclusão de DMA PI gera `ASSERT source=PI`, mas com `MI_LINE=false` porque a máscara PI está desligada.
2. A CPU não entra em exceção enquanto a linha MI permanece mascarada.
3. Ao escrever o comando MI para habilitar PI, o histórico registra `MASK_SET source=PI` com `MI_LINE=true`.
4. No próximo passo, a CPU reconhece a interrupção já pendente e entra no vetor geral `0xFFFFFFFF80000180`.

Isso cobre a diferença entre a fonte estar latched em MI_INTR e a linha externa estar efetivamente habilitada pela máscara. Não demonstra, por si só, que o handler do GoldenEye reconhece corretamente todas as fontes ou que o jogo sai do idle loop.

Commit: `f2e3d0bb46fe7b2eeaca014a5274707692b7dc7b`.

A consulta de workflows associada a esse commit não retornou execuções (o endpoint disponível só lista execuções disparadas por pull request). Portanto, este commit de teste ainda não tem CI verde confirmado nesta documentação.
