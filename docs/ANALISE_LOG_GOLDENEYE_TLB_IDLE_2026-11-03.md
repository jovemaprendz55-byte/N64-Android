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
