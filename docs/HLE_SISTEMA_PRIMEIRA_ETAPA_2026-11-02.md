# HLE de sistema — primeira etapa

**Branch:** `dev/n64-clean-start`  
**Data:** 2026-11-02

## O que foi acrescentado

Foi criado `N64HleOsKernel.kt`, um primeiro modelo host-side de serviços de sistema inspirados em libultra:

- Cadastro e inicialização de threads com validação de ID, prioridade e alinhamento do entry point.
- Dispatcher cooperativo determinístico: menor valor numérico de prioridade vence; empate é FIFO.
- Estados STOPPED, RUNNABLE, RUNNING, WAITING_EVENT e TERMINATED.
- Yield e encerramento da thread atual.
- Filas de mensagens limitadas, com envio/recepção não bloqueantes e FIFO.
- Fila de eventos com sequência monotônica e despertar de uma thread que aguardava evento.
- Snapshots para diagnóstico sem avançar a execução.

## Limite importante: ainda não é interceptação ABI de libultra

Esta etapa é um modelo interno de semântica, não uma implementação completa do sistema operacional do Nintendo 64. Ainda não:

- lê ou escreve estruturas reais OSThread/OSMesgQueue na RDRAM;
- preserva/restaura GPR, FPR, HI/LO, COP0, PC e estado de delay slot por thread;
- liga o dispatcher ao R4300 ou às exceções/interrupções MI;
- implementa espera bloqueante real em send/receive;
- associa eventos a dispositivos concretos (VI, AI, PI, SI, SP);
- executa callbacks ou entry points da ROM;
- agenda OSTask automaticamente ou fabrica descritores OSTask.

Portanto, não afirmar que ROMs já estão usando esse kernel. A integração guest ABI/context switch é o próximo trabalho necessário.

## Testes adicionados

`N64HleOsKernelTest.kt` cobre prioridade/FIFO, yield, encerramento, fila limitada FIFO, despertar por evento e validação do endereço de entrada. Os testes ainda precisam ser confirmados pela CI do commit final.

## Próximas etapas

1. Definir layouts guest de OSThread, OSMesgQueue e OSMesg com offsets e tamanhos validados.
2. Implementar leitura/escrita dessas estruturas pela RDRAM, sem inventar endereços globais.
3. Criar snapshot completo do contexto R4300 e troca de contexto com semântica de exceção/delay slot.
4. Ligar eventos reais MI/VI/AI/PI/SI/SP ao sistema de eventos.
5. Só então adicionar HLE call gates para funções de libultra identificadas com evidência (símbolos, padrões ou endereços conhecidos).

## Estado

Código e testes adicionados na branch. Build, testes CI e execução no Poco C85 ainda não confirmados para estes commits.
