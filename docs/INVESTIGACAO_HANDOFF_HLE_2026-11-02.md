# Investigação do handoff HLE e fluxo de execução — 2026-11-02

Branch: `dev/n64-clean-start`
Escopo: inspeção estática do código atual. Nenhum Kotlin foi alterado, nenhum APK foi instalado e nenhum teste em aparelho foi executado.

## 1. Caminho de execução ativo confirmado

Em `MainActivity.startRomVideoExecution()`:

1. Cria `R4300Cpu(bus)`.
2. Executa `N64HleBoot.prepare(bus, cpu)`.
3. Cria `N64RomVideoSession`.
4. Chama `session.execute()` dentro da thread `N64-ROM-VIDEO-CPU`.

O callback de progresso apenas coleta contadores e atualiza a interface. Não cria threads N64, não agenda OSTasks e não chama `N64SpTaskRunner`.

## 2. O que o HLE realmente inicializa

Em `N64HleBoot.prepare()`:

- Detecta o CIC/seed inspecionando o IPL3 na ROM.
- Lê o entry point do cabeçalho da ROM.
- Copia 1 MiB a partir de ROM offset `0x1000` para RDRAM `0x400`.
- Escreve alguns parâmetros em baixa memória (`0x300` em diante): tipo de TV, tipo/base da ROM, reset, versão IPL2 e tamanho de RDRAM.
- Executa `cpu.reset()`, habilita a TLB, configura `$s3-$s7`, `$sp` e o PC de entrada.

O IPL3 original não é executado. O HLE documenta explicitamente que calibração RDRAM, inicialização de cache, comportamento completo PIF/CIC e temporização de hardware não são modelados.

**Limite importante:** esse handoff é uma aproximação. Não se deve presumir que todos os efeitos laterais do IPL3/PIF e toda a inicialização de hardware esperada pelo jogo estejam presentes. Isso é uma hipótese de compatibilidade a testar, não prova de que essa omissão seja a causa do loop idle.

## 3. Execução da CPU

`N64ExecutionSession.execute()` chama `beforeInstruction`, executa uma instrução por `stepInstruction`, incrementa o contador e chama `afterInstruction`. A sessão cede a thread entre lotes de instruções.

`N64RomVideoSession` usa essa sessão para:
- executar a CPU;
- avançar a contagem aproximada do VI;
- consultar SP em intervalos;
- executar um quantum RSP somente quando SP não está em HALT;
- tentar capturar/publicar frames VI em intervalos.

Esse caminho não equivale a temporização real por ciclos, nem implementa por si só o agendamento de threads libultra ou a submissão de OSTasks.

## 4. Documentação de CPU contradizendo o código

O KDoc no início de `R4300Cpu.kt` ainda afirma que a entrega completa de exceções COP0/TLB não está ligada e que a TLB é opt-in até as exceções estarem conectadas.

A inspeção do código atual encontrou:
- `step()` captura `R4300Tlb.TlbFault` e chama `enterTlbException()`;
- existe caminho de exceções de endereço e instrução reservada;
- existe amostragem de interrupção MI em `serviceMiInterrupt()`;
- `N64HleBoot.prepare()` define `cpu.tlbEnabled = true`.

Isso não significa implementação completa/validada de VR4300. Significa apenas que o comentário atual está desatualizado e deve ser substituído por uma descrição precisa do subset implementado e das limitações reais. A correção do KDoc deve ser um commit separado, sem mudança funcional.

## 5. O que ainda não sabemos

A inspeção estática não permite determinar:
- se os parâmetros de handoff HLE são suficientes para a ROM específica;
- se faltam efeitos de inicialização do IPL3/PIF que a ROM espera;
- qual rotina exata conduz ao loop idle `0x7000071C`;
- se o scheduler da ROM está esperando uma interrupção, uma fila, uma thread ou outro evento;
- se o primeiro desvio de fluxo está relacionado a TLB, a instrução não implementada ou ao ambiente incompleto.

Não atribuir a causa a um único item sem evidência dinâmica.

## 6. Próximo teste técnico recomendado

1. Capturar o estado inicial logo após `N64HleBoot.prepare()`: PC, GPRs, COP0, SP, RA, GP e os parâmetros de baixa memória.
2. Capturar checkpoints em transições de função e no primeiro ingresso no loop idle, incluindo PC anterior, instrução, destino de branch/JR/JALR e registradores de argumentos.
3. Comparar o estado real com os efeitos conhecidos do handoff IPL3/PIF para a ROM-alvo; não escrever valores presumidos em registradores sem fonte.
4. Instrumentar chamadas e retornos de forma limitada para identificar a rotina que deveria ativar threads/filas.
5. Só depois avaliar um teste isolado de inicialização HLE versus outro handoff documentado.
6. Corrigir KDocs desatualizados em commit documental separado.
7. Manter o diagnóstico OSTask/SP anterior como trilha independente; ausência de escritas SP/DPC continua sendo um fato observado, mas não identifica sozinha a causa.

## 7. Estado desta etapa

- Inspecionados MainActivity, N64HleBoot, N64ExecutionSession, N64RomVideoSession e início/fluxo principal de R4300Cpu.
- Confirmado que a execução é CPU em thread Android com polling cooperativo RSP/VI.
- Confirmado que HLE não executa IPL3/PIF completo.
- Confirmado KDoc desatualizado em R4300Cpu.
- Nenhuma alteração Kotlin ou funcional.
- Nenhum APK gerado/testado nesta etapa.
- Branch `main` não alterada.
