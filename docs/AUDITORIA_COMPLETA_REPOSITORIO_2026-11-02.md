# Auditoria global do repositório — 2026-11-02

Branch de trabalho: `dev/n64-clean-start`  
Repositório: `jovemaprendz55-byte/N64-Android`  
Objetivo obrigatório: HLE independente da PIF ROM/firmware e sem executar o IPL3 original.

## 1. Inventário verificado

Árvore Git consultada recursivamente no commit `7c6835098f93767fadb73b82cc6f65ea52d7fb31`:

- 117 entradas na árvore;
- 102 arquivos (blobs);
- 35 arquivos de produção em `app/src/main`;
- 56 arquivos de testes em `app/src/test`;
- 5 documentos em `docs`;
- 6 arquivos de configuração/README/workflow.

A árvore não estava truncada. Foram inspecionados os módulos centrais de Activity, HLE, CPU R4300/COP0/TLB, memória, barramento, DMA, sessões de execução, SP/OSTask, RSP, DPC/RDP, VI, ROM loader/inspector, configuração Gradle, manifesto, README e documentos técnicos. O workflow executou a suíte unitária completa do projeto.

## 2. Validação automatizada anterior às correções desta auditoria

O workflow `41423704321`, no commit `7c6835098f93767fadb73b82cc6f65ea52d7fb31`, terminou com sucesso. As etapas `Build and test` (`gradle testDebugUnitTest assembleDebug`) e `Upload debug APK` passaram.

Isso comprova compilação e sucesso dos testes automatizados naquele commit; não comprova compatibilidade de GoldenEye nem teste em aparelho Android.

## 3. Correção encontrada durante a auditoria

### Exportação concorrente dos logs

Em `MainActivity`, a exportação era executada numa thread separada enquanto a thread da ROM ainda podia modificar históricos de CPU/TLB e coleções de diagnóstico do barramento. Algumas dessas coleções são `ArrayDeque`, `LinkedHashMap` e listas mutáveis, não protegidas para iteração concorrente.

Risco: snapshot inconsistente ou exceção durante a exportação, compatível com os relatos de falha ao salvar logs.

Correção aplicada:
- ao iniciar a exportação, captura a thread e a sessão atuais;
- solicita parada cooperativa da sessão;
- aguarda até 5 segundos pela saída da thread;
- aborta a exportação com mensagem explícita se a thread não parar;
- somente depois lê os dados de CPU, TLB, RSP e barramento.

Commit inicial `0f515809a6c7666e5c94b2199baa61f7c39844da` revelou no CI uma duplicação de `companion object`; isso foi corrigido no commit `a04d44bd3fdd9167b9868e631ad6eb75c501bc05`. A validação do commit corrigido deve ser consultada antes de declarar a correção aprovada.

### Documentação desatualizada

O README dizia que RSP, RDP e geração de vídeo ainda não existiam e descrevia boot completo via PIF/IPL3 como etapa futura. O código atual já tem implementações experimentais parciais desses componentes, e o objetivo atual é o HLE sem executar IPL3/PIF original.

README atualizado no commit `6a055ed84acffd6b28e36a1884aee81ae553cc62`, deixando explícitas as capacidades parciais e limitações.

## 4. Estado técnico por subsistema

### HLE / boot
- Detecta atualmente CIC 6102/7101 e 6105/7105 por inspeção do checksum IPL3.
- Copia 1 MiB da ROM a partir do offset `0x1000` para RDRAM `0x400`.
- Prepara parâmetros de baixa memória e registradores GPR documentados.
- Não executa IPL3 original e não usa PIF ROM.
- A correção `osCicId` está documentada e coberta por testes sintéticos.
- Ainda não está provado que o estado COP0/cache/RDRAM reproduz integralmente o estado esperado pelo jogo após o boot real.

### CPU R4300 / COP0 / TLB
- Há execução de subconjunto de instruções MIPS III/VR4300, delay slots, exceções, TLB e operações TLB básicas.
- TLB modela 32 entradas, ASID/global, páginas pareadas, PageMask, valid/dirty e falhas MISS/INVALID/MODIFIED.
- Não é implementação completa nem ciclo-precisa do VR4300.
- O estado inicial COP0, especialmente Status/BEV/Random/Wired, ainda precisa de referência técnica/experimental antes de qualquer alteração.
- Não foi feita alteração funcional na CPU/TLB nesta auditoria: os logs não bastam para atribuir a falha a um único detalhe da MMU.

### Barramento / dispositivos
- Há RDRAM, ROM, PIF RAM e MMIO parcial para SP, DPC, MI, VI, AI, PI, SI e RI.
- PI/SP/SI DMA são simplificados/síncronos; timing, fila e vários efeitos laterais não são completos.
- AI não reproduz áudio; Joybus/controles não estão implementados integralmente.

### SP / OSTask / RSP
- Existe descriptor OSTask, bootstrap explícito, tracker de microcódigo e runner com orçamento de instruções.
- O caminho ativo da ROM não inventa endereço OSTask nem chama o runner sem evidência.
- A sessão cooperativa só executa RSP depois que o software libera SP HALT.
- RSP tem subconjunto escalar e vetorial; microcódigos comerciais e semântica vetorial completa não estão cobertos.

### DPC / RDP / VI
- Parser de display lists e comprimentos de comandos RDP existem.
- RDP executa um subconjunto de comandos fill e rasterização experimental de triângulos planos RGBA16.
- Não há pipeline completo de textura, shade, Z, coverage, blending e combiner.
- VI captura framebuffer com temporização aproximada; não equivale a scanout real/ciclo-preciso.

### Android / ROM / build
- Loader normaliza Z64, V64 e N64.
- UI apresenta ROM e uma tela de vídeo experimental.
- Exportação de logs foi corrigida para parar a execução antes de iterar diagnósticos.
- Gradle/manifesto e workflow usam JDK 17, AGP 8.10.1, Kotlin 2.1.10, Gradle 8.11.1 e compileSdk 36.

## 5. Limitações e riscos que permanecem abertos

1. HLE ainda não provou boot completo de GoldenEye; não declarar jogabilidade.
2. Último log analisado continua sem frames VI, sem instruções RSP e sem atividade DPC/SP útil; CPU termina no loop idle.
3. Investigar TLB refill e retorno do handler com rastreamento temporal consistente, sem misturar sessões.
4. Comparar parâmetros e COP0 do handoff HLE com fontes técnicas confiáveis; não adivinhar BEV/Status/Random/Wired.
5. Expandir testes direcionados a transições de exceção/refill, instruções R4300 e comportamento MMIO somente quando a semântica esperada estiver confirmada.
6. Validar a correção de exportação de logs em CI e, depois, no aparelho Android 16.
7. Não forçar SP HALT, não criar OSTask sintética e não executar IPL3/PIF ROM original.

## 6. Commits desta auditoria e validação pendente

- `0f515809a6c7666e5c94b2199baa61f7c39844da`: primeira correção de concorrência na exportação.
- `6a055ed84acffd6b28e36a1884aee81ae553cc62`: README atualizado.
- `a04d44bd3fdd9167b9868e631ad6eb75c501bc05`: corrige o `companion object` duplicado detectado pelo CI.
- CI do commit `0f515809` falhou por duplicação de `companion object` e referência `REQUEST_ROM` afetada; correção aplicada em `a04d44bd`.
- Workflow do commit `a04d44bd` deve ser consultado antes de declarar o estado final da validação.
- A APK anterior não representa esta correção e não deve ser apresentada como build atual.

## 7. Integridade do repositório

Todo o trabalho desta auditoria foi direcionado à branch `dev/n64-clean-start`. A branch `main` não foi alterada.
