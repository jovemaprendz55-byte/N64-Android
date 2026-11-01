# CONTINUIDADE DO PROJETO — N64 Android

> Documento de passagem de contexto para qualquer novo chat ou colaborador. Leia este arquivo antes de alterar código. Atualize-o sempre que houver mudança relevante, teste, build, log ou decisão técnica.

**Última atualização:** 2026-10-31 (etapa 44 — teste visual sintético do VI confirmado no aparelho pelo usuário)  
**Repositório:** https://github.com/jovemaprendz55-byte/N64-Android  
**Branch de trabalho obrigatória:** `dev/n64-clean-start`  
**Último commit de código da etapa 44 enviado:** `e187a2757551ac000ccc5a4b9e4b244b36dc02c4` (tela de teste visual VI; aguarda CI).
**Último commit documental antes desta atualização:** `cefe58ed989b8ac37d1488c4df9611910463aebf`.
**Commit da auditoria documental:** `eac1f8350c66eb50510b3661e969699840abcd4a`.  
**Commit da integração do coletor:** `3e88607b7011630bc9063f2aa1059cfb2c8563a8`.
**Commits da etapa 43:** `b299c364b7b02ea085a05fac3123b1c66c730874` (componente), `f44aea64089fe95bc3fd31e58dac19cf949ddb81` (testes iniciais), `07705544128ca997102de6a2465a615389f30c12` (integração Activity), `81f64a0190a993fc9917995f6792afc5557e349e` (ajuste do teste).
**HEAD conhecido antes desta atualização documental:** `cefe58ed989b8ac37d1488c4df9611910463aebf` (documentação da etapa 42). O próximo chat deve sempre conferir o HEAD real.
**CI anterior concluído:** build #708, run ID `41235044776`, sucesso; build/testes e upload do APK debug concluídos. A build #707, para o último commit de código, também terminou com sucesso.
**CI da auditoria documental:** build #709, run ID `41240222394`, sucesso.  
**CI da etapa 42:** build #711 (run ID `41250019688`) e build #712 (run ID `41250044868`) concluíram com **success**. **Etapa 43:** build #714 (run ID `41250243896`) passou para compilação do componente; builds intermediárias #715 e #716 falharam por um endereço incorreto no cenário de teste, corrigido no commit `81f64a0...`; build #717 (run ID `41250293247`) concluiu com **success**, incluindo Build and test e Upload debug APK. Build #718 (run ID `41250358376`) valida esta atualização documental.
**Estado documental confirmado:** CI #719 (run ID `41250393284`) concluiu com sucesso, incluindo Build and test e Upload debug APK. A etapa 44 teve os workflows de código #720, #721 e #722 concluídos com sucesso; o workflow documental #723 concluiu com sucesso.

---

## 1. Objetivo do projeto

Construir um emulador Nintendo 64 próprio para Android, em Kotlin, sem incorporar núcleos de emulação de terceiros. O objetivo de compatibilidade em andamento é iniciar e fazer avançar o **GoldenEye 007**, usando inicialização **HLE (High-Level Emulation)**, sem executar o IPL3 original do cartucho e sem depender de uma PIF ROM externa.

A meta HLE não significa remover a emulação do hardware do N64. Continuam necessários CPU R4300/MIPS III, COP0/TLB, RDRAM, barramento, PI/SP DMA, PIF/SI/Joybus (por HLE quando apropriado), RSP, RDP, VI/vídeo, áudio e controles.

**Não declarar que o jogo está inicializando corretamente** até existir evidência nos logs e, depois, imagem/resultado visual real no aparelho.

## 2. Regras que não podem ser quebradas

1. Trabalhar somente em `dev/n64-clean-start`.
2. **Não modificar, atualizar, mesclar ou fazer commit em `main`**, salvo autorização explícita do usuário.
3. Não reiniciar o projeto nem descartar trabalho anterior. Continuar do HEAD e inspecionar o estado atual antes de editar.
4. Não inventar resultados de testes, logs, builds ou comportamento do jogo. Separar claramente confirmado, hipótese e pendente.
5. Antes de implementar estado de CPU/hardware, procurar evidência no código, nos testes, nos logs ou em documentação técnica confiável.
6. Preservar a interface em **portrait/retrato**.
7. O botão de copiar log deve ficar na página de diagnóstico da ROM, sem botões redundantes na tela inicial.
8. O usuário prefere instruções em português, diretas e simples. Ele costuma dizer “Continua” para autorizar a próxima etapa técnica.
9. Depois de cada avanço significativo, atualizar este documento com commit, arquivos, testes, build, evidências, problemas e próximo passo.
10. Não tratar uma build verde como prova de compatibilidade do jogo: ela prova apenas que os passos automatizados daquela build passaram.

## 3. Estado atual confirmado

**Nota de histórico:** o commit abaixo era o estado registrado numa versão anterior deste documento; não é mais o HEAD atual.

```
0ea295fab125cc60fc979e9052c62969a76be59c
Make HLE entry fetch test use deterministic NOP payload
```

**Estado atual confirmado na revisão de 2026-10-31 durante a etapa 43:** último commit de código conhecido `81f64a0190a993fc9917995f6792afc5557e349e`; última documentação anterior a este registro `cefe58ed989b8ac37d1488c4df9611910463aebf`. CI #714 passou para o novo componente; CI #717 ainda valida testes e integração completa. Consulte sempre o HEAD real da branch antes de continuar.

O arquivo `N64HleBoot.kt` contém um handoff experimental que:

- Detecta o seed CIC por meio de `bus.detectCicSeed()`.
- Aceita atualmente as famílias CIC 6102/7101 (seed `0x3F`) e 6105/7105 (seed `0x91`), conforme a implementação atual.
- Copia `0xFC0` bytes do cartucho, a partir do offset ROM `0x1000`, para RDRAM no endereço `0x400`.
- Reinicia a CPU, configura `$s6` (GPR 22) com o seed e `$sp` (GPR 29) com `0xFFFFFFFFA07FFFE0`.
- Inicia o PC em `0xFFFFFFFF80000400` (KSEG0).
- Retorna `pifRomUsed=false`.
- Não copia o IPL3 para SP DMEM e não executa o IPL3.

**Importante:** `detectCicSeed()` ainda lê/soma dados do IPL3 para identificar a família CIC. Portanto, a rotina não executa IPL3, mas consulta os bytes do IPL3 para classificação. Não afirmar que a identificação CIC já foi substituída por uma implementação PIF/CIC completa.

### Arquivos centrais

- `app/src/main/java/com/jsr/n64android/N64HleBoot.kt` — rotina de handoff HLE experimental.
- `app/src/test/java/com/jsr/n64android/N64HleBootTest.kt` — testes unitários do handoff.
- `app/src/main/java/com/jsr/n64android/MainActivity.kt` — fluxo de diagnóstico HLE, execução e exibição/cópia de logs.
- `app/src/main/java/com/jsr/n64android/N64Bus.kt` — barramento e periféricos parciais.
- `app/src/main/java/com/jsr/n64android/N64Memory.kt` — memória.
- `app/src/main/java/com/jsr/n64android/R4300Cpu.kt` — CPU R4300 em desenvolvimento.
- `README.md` — visão geral do projeto; pode precisar de atualização para refletir o HLE experimental atual.

Links diretos:
- [N64HleBoot.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/main/java/com/jsr/n64android/N64HleBoot.kt)
- [N64HleBootTest.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/test/java/com/jsr/n64android/N64HleBootTest.kt)
- [MainActivity.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/main/java/com/jsr/n64android/MainActivity.kt)
- [N64Bus.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/main/java/com/jsr/n64android/N64Bus.kt)
- [N64Memory.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/main/java/com/jsr/n64android/N64Memory.kt)
- [R4300Cpu.kt](https://github.com/jovemaprendz55-byte/N64-Android/blob/dev/n64-clean-start/app/src/main/java/com/jsr/n64android/R4300Cpu.kt)

## 4. Auditoria HLE: o que foi descoberto

Na auditoria de 2026-10-31, a rotina anterior chamada HLE não era HLE independente: copiava `0xFC0` bytes do IPL3 do cartucho para SP DMEM em `0x04000040` e iniciava o PC em `0xFFFFFFFFA4000040`, executando o IPL3 real.

Essa implementação foi identificada como handoff/execução de IPL3 com preparação parcial, não como substituição HLE. A rotina foi preservada com nome legado `runIpl3HandoffDiagnosticLegacy`; a nova rotina de diagnóstico chama `N64HleBoot.prepare`.

Limitações identificadas na auditoria:
- Identificação CIC: parcial.
- PIF RAM/protocolo PIF: não implementado de forma completa.
- Desafio/resposta CIC: não implementado de forma completa.
- Estado de CPU/COP0 de handoff: parcial e ainda não comprovado.
- RDRAM e preparação de memória: parciais.
- PI DMA: básico/síncrono, sem temporização e interrupções completas.
- SP DMA: básico.
- RSP: ausente/incompleto.
- RDP e geração gráfica/VI: ausentes/incompletos.
- Áudio e controles: ausentes/incompletos.

O `cpu.reset()` zera registradores gerais, COP0, FPU e TLB; o handoff atual configura apenas alguns GPRs e o PC. `tlbEnabled` é uma flag interna do emulador e não substitui os registradores COP0. É necessário determinar o estado correto de handoff com evidência, não por palpite.

## 5. Testes atuais do handoff

`N64HleBootTest.kt` constrói uma ROM sintética e verifica:

- Reconhecimento do seed CIC 6102/7101 (`0x3F`) usando checksum sintético.
- Quantidade e endereços de cópia do payload.
- Configuração de PC, SP e GPR 22.
- `pifRomUsed=false`.
- Comparação byte a byte do payload copiado para RDRAM.
- Busca da primeira instrução através de KSEG0/RDRAM: payload começa com NOP; um passo avança PC em 4.
- SP DMEM permanece sem IPL3.
- CIC desconhecido é rejeitado antes de alterar o estado da CPU.

Esses testes validam o handoff e a busca de instrução sintética; **não provam que o bootcode real do GoldenEye funciona**.

## 6. Diagnóstico e logs

**Registro histórico:** a primeira versão de `runHleBootDiagnostic()` tinha limite de 500.000 instruções. As etapas posteriores alteraram o limite; o fluxo atual usa `N64HleDiagnosticRunner` e `N64CpuExecutionSession`, com limite documentado de 2.000.000 instruções. Não usar esta descrição antiga como especificação do código atual.

A rotina `runHleBootDiagnostic()` foi preparada originalmente para:
- Chamar `N64HleBoot.prepare`.
- Executar até 500.000 instruções ou parar em exceção.
- Capturar os primeiros 48 passos.
- Registrar checkpoints a cada 50.000 instruções.
- Capturar PC, instrução, próximo PC e registradores relevantes.
- Registrar os 32 GPRs.
- Registrar COP0 Status, Cause, EPC e BadVAddr.
- Registrar exceções e último fault TLB (tipo, acesso e endereço virtual).
- Abrir a página de diagnóstico da ROM, onde fica o botão de copiar logs.

Mensagens do diagnóstico incluem `BOOT HLE — IPL3 NÃO EXECUTADO`, seed CIC, origem/destino do payload, PC/SP iniciais e estado final. Conferir o código atual antes de mudar o formato.

### Logs históricos que não devem ser confundidos

- O log de HLE experimental reportou seed `0x3F`, payload `0xFC0` do offset `0x1000` para RDRAM `0x400`, PC `0xFFFFFFFF80000400`.
- Logs antigos do caminho IPL3 começavam em `0xFFFFFFFFA4000040` e mostravam exceções/falhas TLB. Eles pertencem ao caminho legado, não ao novo handoff HLE.
- Diagnósticos antigos de GoldenEye incluíram PCs como `0xFFFFFFFF800001C4`, vetor de exceção `0xFFFFFFFF80000180`, EPC `0xFFFFFFFFB0000428`, loops em torno de `0xFFFFFFFFB0000450` e chegada ao endereço `0xFFFFFFFF80000000` em uma execução com IPL3. Não usar esses resultados como prova do novo HLE.

## 7.1. Instrumentação adicional do diagnóstico HLE — 2026-10-31

No commit `bbd964e0b9759df93ab9b38a797317003163ff60`, `MainActivity.kt` passou a acrescentar ao relatório HLE uma seção `EVENTOS DO BARRAMENTO`, sem alterar o comportamento da CPU ou simular hardware adicional.

O relatório agora inclui:
- Histórico recente de requisições PI DMA e SP DMA.
- Contagem total e amostras recentes de escritas na RDRAM.
- Acessos registrados na região de memória monitorada pelo diagnóstico legado.
- Contagem e amostras de escritas na região de destino monitorada pelo diagnóstico legado.
- Registradores SI DRAM, PIF read address, PIF write address e SI status.
- Snapshot hexadecimal dos 64 bytes da PIF RAM.

Objetivo: quando o usuário executar o HLE no aparelho, o log mostrará se o bootcode solicita DMA, acessa SI/PIF ou altera a PIF RAM. Isso ajuda a localizar a primeira dependência de hardware sem inventar respostas HLE antes de observar o comportamento real.

Esses campos são observabilidade/diagnóstico. **Não significam que o protocolo PIF/Joybus/CIC esteja implementado.** A região de memória monitorada pelo legado é específica e não representa todos os acessos do bootcode.

Build acionada para esse código:
- Build #595, run ID `41180363612`.
- Commit testado: `bbd964e0b9759df93ab9b38a797317003163ff60`.
- Resultado final: **success** (Build and test e Upload debug APK concluídos com sucesso).
- Artifact: `n64-android-debug-apk`, ID `13034074585`, tamanho 885.882 bytes, SHA-256 `ab83ecc9a66df3617149ba8489aeda169424ca5967a5476a1033a0798a52d594`.
- Expira em 2026-11-14 07:23:48 UTC (confirmar validade no momento do acesso).
- Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41180363612

## 7. Builds GitHub Actions

### Build #592 — última verificação registrada

- Run ID: `41179757924`
- Commit: `0ea295fab125cc60fc979e9052c62969a76be59c`
- Branch: `dev/n64-clean-start`
- Resultado: **success**
- Job: `build`, concluído com sucesso.
- Passos `Build and test` e `Upload debug APK`: sucesso.
- Artifact: `n64-android-debug-apk`
- Artifact ID: `13034700589`
- Tamanho: 884.957 bytes.
- SHA-256 informado pelo GitHub: `02e5321d67b68be4b874c1ba0bb20d1957a2372a8947a9e78393393f7a503745`
- Expira em: 2026-11-14 07:14:37 UTC (confirmar validade no momento do acesso).

Links:
- [Build #592](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41179757924)
- [Artifacts da build #592](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41179757924#artifacts)

Build #589 (run ID `41179440281`) também foi registrada como sucesso, mas #592 é mais recente. Não presumir que o usuário instalou a APK #592: perguntar/confirmar antes de interpretar logs do aparelho.

## 8. Estado atual e próximos passos — atualizado em 2026-10-31

### Estado confirmado
- Branch obrigatória: `dev/n64-clean-start`; não alterar `main`.
- Último commit de código conhecido: `81f64a0190a993fc9917995f6792afc5557e349e`.
- Último commit documental antes desta atualização: `cefe58ed989b8ac37d1488c4df9611910463aebf`.
- Etapa 42: build #711 (run ID `41250019688`) e build #712 (run ID `41250044868`) concluíram com sucesso.
- Etapa 43: build #714 (run ID `41250243896`) passou para compilação do componente; builds #715 e #716 falharam por cenário de teste incorreto, corrigido em `81f64a0...`; build #717 (run ID `41250293247`) concluiu com sucesso, incluindo Build and test e Upload debug APK. Artifact `n64-android-debug-apk`, ID `13060674754`, 914.918 bytes, expira em 2026-11-14 22:56:08 UTC.
- `N64HleInstructionTrace` está integrado ao fluxo `runHleBootDiagnostic()`.
- `N64HleCopyLoopTrace` foi extraído e integrado ao fluxo HLE principal. O teste de amostragem foi corrigido no commit `81f64a0...`; considerar o resultado final somente após CI #717.
- O método legado `runIpl3HandoffDiagnosticLegacy()` ainda mantém instrumentação local própria.
- O diagnóstico HLE continua limitado a 2.000.000 instruções. Não aumentar esse limite para ocultar falhas ou loops.
- VI/framebuffer é experimental; RSP/RDP, scanout temporizado/VBlank e apresentação contínua não estão implementados de forma suficiente para afirmar que um jogo renderiza corretamente.
- CI verde comprova somente compilação/testes automatizados e upload do APK. Não comprova jogo testado ou funcionando no aparelho.

Links:
- [Build #711 — integração do coletor HLE](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250019688)
- [Build #712 — documentação da etapa 42](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250044868)
- [Build #714 — componente do laço de cópia](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250243896)
- [Build #717 — teste corrigido e integração completa](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250293247)
- [Build #718 — documentação da etapa 43](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250358376)

### Próxima etapa técnica recomendada
1. Confirmar o resultado de CI #718, que valida esta atualização documental.
2. Revisar o diff final da etapa 43 se algum novo log do usuário indicar divergência no formato do rastreamento.
3. Depois, avaliar a extração dos checkpoints/primeiras 48 instruções para o runner, sem alterar a ordem ou o formato do relatório.
4. Preservar a captura VI única, snapshots MMIO/periféricos, logs de falha, limites de instruções e cancelamento.
5. Não avançar para RSP/RDP nem alegar renderização funcional com base em refatorações de diagnóstico.

### Regras
- Não alterar `main`; não fazer merge ou reset.
- Não executar loops infinitos nem transformar o diagnóstico limitado em game loop.
- Não simular RSP/RDP, PIF/CIC ou dispositivos sem evidência e testes.
- Preservar UI portrait e o botão de copiar log na página de diagnóstico.
- Não presumir instalação do APK.

## 9. Como o próximo chat deve continuar

A primeira ação é ler este documento e conferir o HEAD real de `dev/n64-clean-start`. Confirmar o resultado de CI #717 antes de editar novamente. Inspecionar `N64HleCopyLoopTrace.kt`, seus testes e o fluxo `runHleBootDiagnostic()`.

**Não alterar `main`. Não presumir que o APK foi instalado. Não dizer que GoldenEye ou outro jogo já funciona.**

Mensagem de arranque recomendada:

> Continue o projeto N64 Android a partir de `docs/CONTINUIDADE_CHAT.md` na branch `dev/n64-clean-start`. Confira o HEAD real e o CI #717 antes de editar. A etapa 43 extraiu `N64HleCopyLoopTrace` da Activity e adicionou testes para limites, amostragem e janela de 64 instruções após a saída do laço. Preserve o formato e a ordem dos logs. Continue com pequenas extrações testáveis, sem tocar em `main`. CI verde não comprova jogo funcionando no aparelho.

## 10. Histórico de atualização deste documento

- **2026-10-31:** criado documento inicial de passagem de contexto; registrado handoff HLE experimental, auditoria, testes, limitações, branch/commit e build #592.
- **2026-10-31:** diagnóstico HLE ampliado com eventos PI/SP DMA, escritas RDRAM, estado dos registradores SI e snapshot PIF RAM; build #595 concluída com sucesso e APK de diagnóstico gerada.
- **2026-10-31:** após o primeiro log real HLE (125.556 instruções, falha em leitura física 0x70000510), o catch do diagnóstico foi ampliado para preservar instrução, acesso, registradores, COP0/TLB e barramento; commit `227040724945b051343dce2ab9d6aed41b9e3088`; build #598 em fila.
- **2026-10-31:** etapas 33–40 registraram integração progressiva do worker/sessão R4300, extração do runner e do resultado estruturado, preservação de falhas e criação do coletor limitado de rastros. Os detalhes permanecem nas seções 33–40.
- **2026-10-31:** auditoria do documento comparou resumo, commits, CI e código. Identificou cabeçalho e próximos passos obsoletos, corrigidos nesta atualização. Confirmado que o coletor da etapa 40 ainda não está integrado à `MainActivity`.
- **2026-10-31:** CI #707 (run ID `41234995195`) validou o commit de código `f8cf8ac8b24139289f3cc7c1475580ba9ff98f10`; CI #708 (run ID `41235044776`) validou a atualização de documentação `fcce62293ce65b871bcf04d20894b7da9f2cd27a`. Ambos com Build and test e Upload debug APK em sucesso.
- **2026-10-31:** etapa 42 integrou o coletor limitado `N64HleInstructionTrace` ao fluxo HLE principal; CI #711 (run ID `41250019688`) e CI #712 (run ID `41250044868`) concluíram com sucesso, incluindo Build and test e Upload debug APK. Nenhum teste em aparelho foi realizado/confirmado.
- **2026-10-31:** etapa 43 extraiu `N64HleCopyLoopTrace`, integrou-o ao fluxo HLE principal e criou testes de limites/amostragem/janela pós-saída. Após corrigir a fixture de teste, build #717 concluiu com sucesso, incluindo Build and test e Upload debug APK.

## 11. Primeiro log real do HLE com contexto preservado — 2026-10-31

O usuário executou GoldenEye com a build que contém o catch ampliado e enviou o relatório completo:

- HLE confirma `PIF ROM utilizada: false`, seed CIC `0x3F`, payload de 4032 bytes copiado de ROM `0x1000` para RDRAM `0x400`, PC inicial `0xFFFFFFFF80000400`.
- O bootcode executou **125.556 instruções** antes da falha.
- Foram registradas **204.864 escritas na RDRAM**; não houve PI DMA nem SP DMA.
- SI permaneceu em `DRAM=0`, `PIF_RD=PIF_WR=0x1FC007C0`, `STATUS=0`; os 64 bytes de PIF RAM permaneceram zerados.
- Falha: `UnsupportedOperationException: Unmapped N64 physical read at 0x70000510`, durante `FETCH`; VADDR e PADDR reportados iguais a `0x70000510`.
- No momento da falha: `r4=r5=0x70000000`, `r10=0x70000510`, `r2=1`, `r3=0x1F`, `r6=0x1F`, `r7=1`, `r8=0x7FE000`, `$sp=0xFFFFFFFF803AB410`; COP0 Status/Cause/EPC/BadVAddr zerados; nenhum TLB fault arquitetural registrado.
- O diagnóstico mostrou `INST=N/A` porque a falha ocorreu ao buscar a instrução, antes de a CPU conseguir ler a palavra. `Último destino JR/JALR=N/A` não era prova de ausência de salto: o campo era apagado no início de cada instrução, inclusive antes de um fetch que falhasse.

Interpretação cuidadosa: a execução avançou e escreveu memória, mas o endereço `0x70000510` não foi explicado ainda. `0x70000000` está fora de KSEG0/KSEG1 e, portanto, exige compreender o estado de tradução/endereço do handoff. Não habilitar TLB às cegas nem criar mapeamento físico para `0x70000000`; primeiro identificar a transferência de controle e o estado arquitetural que a precedeu.

### Correção de diagnóstico em andamento

Foi preparado um ajuste estritamente observacional em `R4300Cpu.kt` e `MainActivity.kt`: preservar o último JR/JALR executado (PC de origem, palavra da instrução e destino) mesmo após instruções subsequentes; incluir no relatório a flag interna TLB e COP0 Index, Wired e EntryHi; acrescentar teste unitário para garantir que instruções posteriores não apaguem a evidência do JR.

Não foram alterados tradução de endereços, TLB, estado de boot ou dispositivos. O objetivo é obter evidência antes de qualquer correção funcional.

Builds anteriores ao ajuste observacional:
- #598 — run ID `41181156270`, commit `227040724945b051343dce2ab9d6aed41b9e3088`, sucesso. Artifact ID `13035231089`, SHA-256 `b01176eb0a68cb0e0c227026f6ad00a1d10c520a418b36377bdba02c8e6ff005`.
- #599 — run ID `41181207832`, commit `12685cb014d31dbfce972bd597f58734d4d5a7d8`, sucesso. Artifact ID `13034509641`, SHA-256 `b05b83f0175ed291fcdfe113302f891ce48562e2b4e68da50cee15ab0689cbac`.

A nova alteração precisa de CI próprio antes de ser considerada pronta para instalação.
## 12. Segundo log real do HLE e correção de tradução TLB — 2026-10-31

O usuário executou a build #604 e o relatório identificou:
- Último salto: `JR $t2` em `0xFFFFFFFF800004B4` (instrução `0x01400008`), destino `0x70000510`.
- `$t2` (r10) contém `0x70000510`; r4/r5 contêm `0x70000000`.
- `tlbEnabled=false`, COP0 Index `1`, Wired `0`, EntryHi `0x70000000`.
- O fetch falha ao tentar ler `0x70000510`.

Auditoria de `R4300Cpu.physicalAddress()` confirmou que KSEG0/KSEG1 (segmentos 4/5) são traduzidos diretamente, enquanto KUSEG e outras regiões passam por `tlb.translate()` somente quando `tlbEnabled=true`. Com a flag falsa, o emulador usa um modo de compatibilidade que trata o endereço virtual como físico. A TLB de 32 entradas é limpa em `cpu.reset()`, e as instruções TLBWI/TLBWR instalam entradas sem ativar por si só a flag.

### Correção aplicada e validada no CI
- Commit `3acd80aa15e8213a7a54c04da74e7125cf024bc1`: `N64HleBoot.prepare()` ativa `cpu.tlbEnabled=true` imediatamente após `cpu.reset()`. O PC inicial `0xFFFFFFFF80000400` continua em KSEG0 e não depende de uma entrada TLB.
- Commit `d909f2a115ba0f21ec01b7623524912964432cf0`: teste `N64HleBootTest` verifica que o handoff ativa a tradução TLB.
- Commit `0f5e1cca5d47244701c6493015eabf43fc103681`: diagnóstico HLE registra a tradução do último destino JR/JALR e o histórico de TLBWI/TLBWR.
- Nenhuma entrada artificial foi criada para `0x70000000` ou `0x70000510`.

CI:
- Build #605 (run ID `41182207941`): sucesso, build/test e upload APK.
- Build #606 (run ID `41182210147`): sucesso, build/test e upload APK.
- Build #607 (run ID `41182212696`): sucesso, build/test e upload APK.
- Artifact #607: `n64-android-debug-apk`, ID `13035427541`, 888.116 bytes, SHA-256 `e4c924508f22632c4d08c497d6ef8118c338834f829d01133dfc023e19aec128`, expira em 2026-11-14 07:51:42 UTC.
- Link da build #607: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41182212696

Próximo passo: instalar a APK #607, executar GoldenEye com HLE e enviar o novo log. Verificar especialmente se o destino `0x70000510` agora é traduzido por uma entrada válida, se aparece TLB MISS/INVALID/MODIFIED, e os valores registrados por TLBWI/TLBWR. Se ocorrer TLB miss, usar EntryLo/PageMask/ASID reais para investigar; não inventar mapeamento.


## 13. Correção do tamanho da imagem carregada pelo HLE — 2026-10-31

O log `Logi` da branch `main` foi analisado. A execução alcançou o fim do laço de cópia:
- 78.240 iterações; 78.239 branches tomados; saída observada.
- O laço lê a origem física RDRAM `0x20D90..0x33F2F` (virtual KSEG0 `0x80020D90..0x80033F2F`) e escreve o destino físico `0x1EE400..0x20159F`.
- As leituras na origem eram zero, e o código copiado foi executado em `0x7020141C`, levando à exceção RI (código 10) em `0x703AB3F8`.
- PI DMA e SP DMA permaneceram em zero.

### Causa confirmada no código HLE anterior

`N64HleBoot.prepare()` copiava somente `0xFC0` bytes (4032 bytes) do ROM `0x1000` para RDRAM `0x400`. Porém, o boot padrão do N64 copia o primeiro **1 MiB** do cartucho, começando no ROM `0x1000`, para RDRAM `0x400` antes de entrar no código do jogo. O endereço físico `0x33F2F` fica dentro dessa janela de 1 MiB e, portanto, deveria conter dados da ROM, não zeros. O mapeamento correspondente é ROM offset `0x34B2F` (`0x1000 + (0x33F2F - 0x400)`).

Referências técnicas consultadas:
- https://reverseengineering.wiki/index.php/Platform%3ANintendo_64 — descrição do boot padrão, cópia de 1 MiB e parâmetros em `0x80000300`.
- https://ultra64.ca/files/documentation/online-manuals/man/pro-man/pro06/06-10.html — manual de programação N64, boot procedure.
- https://github.com/decompals/N64-IPL — disassemblies correspondentes de IPL1/2/3.

### Correção implementada na branch de trabalho

- `N64HleBoot.kt` agora prepara `0x100000` bytes (1 MiB) do ROM `0x1000` em RDRAM `0x400`.
- O PC é obtido do entry point do cabeçalho da ROM (offset `0x08`), validado como endereço KSEG0.
- São escritos os parâmetros básicos de boot em RDRAM `0x300..0x318`: tipo de TV, tipo/base de ROM, reset frio, versão IPL2 e tamanho RDRAM.
- Registradores `$s3..$s7` recebem tipo de ROM, TV, reset, seed CIC e versão IPL2.
- O IPL3 continua sem ser copiado para SP DMEM ou executado. A detecção do seed CIC ainda consulta os bytes IPL3.
- `N64HleBootTest.kt` agora verifica a cópia de 1 MiB, os parâmetros de boot e, em particular, a presença do byte na origem física `0x33F2F`, que antes era zero. O offset ROM esperado é `0x34B2F`.

Commits:
- `afacb2542de488eaaccf36817b29044c1c77bf74` — `fix: stage full first MiB for HLE game entry`.
- `60c2aa9dc7a32b11f7756fba81e6e5df6f002e12` — `test: verify HLE first MiB and boot flags`.
- `9cfcee76d3b78e3351ca5bf78eab5cc403ab950b` — corrige o offset esperado no teste de mapeamento RDRAM→ROM.

CI:
- Build #612 (run ID `41183951900`) disparada para o commit de código `afacb254`.
- Build #613 (run ID `41183954348`) disparada para o commit de teste `60c2aa9`; no momento deste registro estava em andamento. Verificar conclusão e logs antes de disponibilizar APK.
- HEAD da branch após o ajuste de teste: `9cfcee76d3b78e3351ca5bf78eab5cc403ab950b`.

Próximo passo: verificar a build #613. Se passar, instalar a APK resultante e executar novamente o diagnóstico HLE do GoldenEye. Espera-se que a cópia do laço não leia mais zeros na região de origem e que a sequência de execução avance além do antigo destino `0x7020141C`. Isso ainda não prova boot completo: observar a próxima exceção, DMA, SI/PIF e primeiro estado real de vídeo.


## 14. Build #613 — HLE com imagem de 1 MiB — 2026-10-31

A build #613 foi verificada diretamente no GitHub Actions e concluiu com sucesso:
- Run ID: `41183954348`.
- Branch: `dev/n64-clean-start`.
- Commit testado: `60c2aa9dc7a32b11f7756fba81e6e5df6f002e12` (`test: verify HLE first MiB and boot flags`).
- Job `build`: concluído com `success`.
- Etapas `Build and test` e `Upload debug APK`: `success`.
- Artifact: `n64-android-debug-apk`, ID `13035951188`, tamanho 891.178 bytes.
- SHA-256: `7f64207c8f0b19e08ae2b807c7b3e6434ae84ec8b5e1b1a929e669146fd702a8`.
- Artifact não expirado no momento da verificação; expiração em 2026-11-14 08:17:53 UTC.
- Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41183954348

**Interpretação:** CI verde confirma compilação, testes automatizados e upload do APK. Não confirma que GoldenEye inicia ou apresenta imagem no Android.

### Próxima ação obrigatória
1. Usuário instala o artifact da build #613.
2. Executa GoldenEye pelo fluxo `Iniciar jogo (HLE sem IPL3)`.
3. Copia e envia o relatório completo da página de diagnóstico.
4. Analisar se as leituras na origem física `0x20D90..0x33F2F` agora contêm dados da ROM, se a execução ultrapassa o antigo endereço `0x7020141C`, e qual é a primeira nova exceção ou dependência de hardware.
5. Não alterar `main`; não declarar boot completo sem evidência visual real.


## 15. Primeiro log do HLE com staging de 1 MiB — 2026-10-31

O usuário enviou o arquivo `main/Logi` após instalar/executar a build #613. Blob do log consultado: `37a170cadde75992abfc71f503738cf3fe948e0b`.

### Evidências confirmadas no relatório
- HLE: `PIF ROM utilizada: false`, seed CIC `0x3F`.
- Payload de `1048576` bytes (1 MiB), ROM `0x1000` -> RDRAM `0x400`; PC inicial `0xFFFFFFFF80000400`.
- A execução atingiu o limite de **2.000.000 instruções**, sem exceção CPU e sem fault TLB registrado.
- O laço de cópia em `0x70000588` terminou após 78.240 iterações; 78.239 branches tomados e saída observada.
- O fluxo ultrapassou o antigo trecho `0x7020141C` e executou diversas rotinas em `0x702014xx`, `0x702013xx`, `0x702012xx`, `0x70200xxx`.
- Foram contabilizadas 1.549.312 escritas RDRAM; 166.964 escritas na faixa de destino monitorada.
- PI DMA count=0, SP DMA count=0. SI DRAM=0, PIF_RD/PIF_WR=`0x1FC007C0`, SI STATUS=0. PIF RAM permaneceu zerada.
- Estado final: PC `0x702009C8`; sem exceção; limite de instruções alcançado.

### Interpretação técnica
A correção de 1 MiB resolveu a falta de dados na origem do laço: a CPU agora executa o código que foi copiado e avança muito além do ponto que anteriormente falhava com instruções inválidas. Isso é progresso real no caminho HLE, mas não demonstra que o jogo iniciou.

O relatório atual não mostra o que a CPU estava fazendo imediatamente antes de parar em `0x702009C8`: os 64 passos após a saída do laço são capturados apenas perto do início, e os checkpoints a cada 50 mil instruções não mostram o fluxo final. Também não há histórico de leituras/escritas MMIO nem um resumo de PCs mais frequentes. Portanto, ainda não é seguro afirmar que o PC final é um loop de espera por SI, VI, RSP ou outro dispositivo. O estado SI/PIF zerado e ausência de PI/SP DMA são fatos, mas não identificam sozinhos a causa da estagnação.

### Próxima etapa de engenharia (diagnóstico observacional, sem inventar HLE)
1. Instrumentar um ring buffer com as últimas 256 instruções (PC, opcode, próximo PC e registradores relevantes), sempre atualizado durante a execução.
2. Contabilizar frequência dos PCs/loops e registrar os 10 PCs mais visitados, para identificar o laço quente que consome as instruções.
3. Acrescentar histórico limitado de acessos CPU a registradores MMIO (leituras e escritas, endereço físico, valor e PC da instrução), cobrindo MI, VI, AI, PI, SP, SI e RI; manter contadores totais sem formatar strings por acesso.
4. Registrar snapshot final dos registradores de estado dos dispositivos, incluindo VI, MI, SP, PI, AI, SI e RI.
5. Preservar a execução atual de 2 milhões como limite inicial; se necessário, aumentar de forma controlada depois que o ring buffer mostrar o ponto de espera. Não adicionar respostas artificiais de dispositivos nem habilitar DMA sem evidência.
6. Criar testes unitários para a instrumentação e manter UI portrait e botão de copiar log na tela de diagnóstico.

Branch `dev/n64-clean-start` estava no commit `35a72e4af0d7a90e4664aab30e494489b38a0844` após registrar a build #613. `main` contém o arquivo de log enviado; não foi modificada nesta análise.

## 16. Instrumentação de instruções e MMIO — 2026-10-31

### Build #619 (instrumentação de fluxo)
- Commit d6c48e24c246e6923a1b39b755870c24bc719322 — ring buffer das últimas 256 instruções e PCs mais visitados.
- Run ID 41185078343, build #619, branch dev/n64-clean-start.
- CI concluída com success; etapas Build and test e Upload debug APK passaram.
- Artifact n64-android-debug-apk, ID 13036108672, tamanho 892.492 bytes, SHA-256 fe3cef20ddf5b558cb15896a61b576be20413cf2734e795d5fbae3bfeb9b31ee.
- Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41185078343
- Expiração do artifact: 2026-11-14 08:34:18 UTC.

### Instrumentação MMIO em andamento
- N64Bus.kt: adiciona PC virtual corrente fornecido pelo loop HLE, ring buffer limitado a 256 leituras/escritas de registradores MMIO, contagem exata por dispositivo e eventos com operação, dispositivo, PC, endereço físico e valor.
- Cobertura do trace: MI, VI, AI, PI, SP, SI e RI.
- MainActivity.kt: informa PC antes de cada cpu.step() e inclui contagens/histórico MMIO no relatório HLE normal e no relatório de falha.
- Commits em sequência:
  - f8e552701fdd1ba5f7e00e62afd0f9839bb80807 — instrumentação base do barramento.
  - d075ff71834c3830826729ad633fd70a4016e558 — correção das expressões Kotlin do registro de leituras.
  - 803ea7f53a235c9dfbd98e9fce9f0dd92a778575 — inclusão do histórico MMIO no relatório HLE.
  - a4ccccabc9401525d0319082915be6dea704a753 — teste unitário de histórico MMIO e limite 256.
- Atenção: os commits MMIO acabaram de ser enviados em sequência; verificar a execução CI mais recente antes de considerar a alteração compilada ou distribuir APK.
- Teste N64BusMmioDiagnosticTest.kt: valida leitura MI_VERSION, escrita VI, PC/endereço/valor no evento, contadores e retenção dos últimos 256 eventos.

### Próximos passos
1. Verificar a CI disparada pelo commit a4ccccabc9401525d0319082915be6dea704a753.
2. Se falhar, corrigir Kotlin/testes somente em dev/n64-clean-start.
3. Se passar, atualizar este documento com run/artifact e disponibilizar o APK para o usuário instalar e executar o HLE do GoldenEye novamente.
4. No log novo, analisar as últimas 256 instruções, PCs quentes, acessos MMIO por dispositivo e contagens. A instrumentação registra observações; não implementar respostas falsas para dispositivos.
5. Em uma etapa seguinte, complementar com snapshots de estado VI/MI/SP/PI/AI/SI/RI e testes específicos.
6. main continua intocada.
## 17. MMIO e snapshot dos dispositivos — 2026-10-31

### Build #625 — trace MMIO validado
- Commit e83a0f4914cbf344d253d7b2fb228f6f250c3966 corrigiu a faixa de registradores AI (0x18 bytes; não havia constante AI_REG_SIZE).
- Run ID 41185356161, build #625, branch dev/n64-clean-start.
- CI concluída com sucesso; Build and test e Upload debug APK passaram.
- Artifact n64-android-debug-apk, ID 13036728083, tamanho 893.661 bytes.
- SHA-256 d5b6d1dedeb60ff25aa6f77a3ab0e6b2d680edbbac680cc04050bda07f9d6590.
- Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41185356161
- Expiração do artifact: 2026-11-14 08:38:14 UTC.

### Snapshot final dos dispositivos — nova alteração
- N64Bus.diagnosticDeviceSnapshot() gera snapshot sem chamar read32, portanto não acrescenta eventos artificiais ao histórico MMIO.
- Inclui estado implementado de SP, MI, PI, AI, SI, RI e VI.
- MainActivity adiciona o snapshot ao relatório normal e ao relatório de falha.
- Teste N64BusMmioDiagnosticTest.deviceSnapshotReportsImplementedStateWithoutAddingMmioEvents verifica estados alterados e ausência de novos eventos ao consultar o snapshot.
- Commits:
  - 9a814b17f63507ee3a3748561b0840e7dd56c4d9 — snapshot observacional do barramento.
  - 3992d99414169166ae437b0de298a8994e890031 — snapshot anexado ao relatório.
  - 69af94efd0b0fb693c1cd2c228a789f915dc99aa — teste do snapshot.
- CI dos commits 626, 627 e 628 estava em execução no momento deste registro; verificar resultado antes de distribuir APK.
- Links:
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41185471361
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41185486463
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41185506844

### Próximos passos
1. Verificar as builds 626–628 e a build de documentação subsequente.
2. Quando a CI estiver verde, instalar o APK mais recente e executar o GoldenEye HLE.
3. Enviar o novo relatório. Usar ring buffer final de instruções, PCs quentes, trace MMIO e snapshots para identificar dependências de dispositivo.
4. Não inferir que qualquer dispositivo precisa de HLE adicional sem evidência do trace.
5. main permanece intocada.

## 18. Correção do teste de snapshot MI — 2026-10-31

A inspeção da falha das builds #628 e #629 identificou que o teste usava o comando MI_INTR_MASK incorreto. O registrador usa pares de bits: clear no bit `2*n`, set no bit `2*n+1`; para habilitar a fonte SI (bit de máscara 1), o comando é `1 shl 3`, e não `1 shl 1` (que habilita SP, máscara 0). A implementação do barramento segue essa regra; o erro estava na expectativa/entrada do teste, não no snapshot.

- Arquivo alterado: `app/src/test/java/com/jsr/n64android/N64BusMmioDiagnosticTest.kt`.
- Ajuste: comando do teste mudou de `1 shl 1` para `1 shl 3`, mantendo a expectativa `INTR_MASK=0x2`.
- Builds #628 e #629 falharam em `deviceSnapshotReportsImplementedStateWithoutAddingMmioEvents`, linha 55; as demais 285 verificações passaram.
- Próxima validação: executar novamente `testDebugUnitTest assembleDebug` na branch `dev/n64-clean-start`. Não alterar `main`.


## 19. Auditoria da interface e do caminho de vídeo — 2026-10-31

### Capturas examinadas
- Tela inicial publicada em `main/Screenshot_2026-10-31-06-11-12-123_com.jsr.n64android.jpg`.
- Tela de diagnóstico HLE publicada em `main/Screenshot_2026-10-31-06-08-27-733_com.jsr.n64android.jpg`.
- As capturas mostram uma interface funcional de protótipo: identificação da ROM, metadados, seleção de ROM e ações de diagnóstico; a página HLE mostra texto de execução e botão de copiar log.
- A tela inicial está em retrato, coerente com o manifesto atual (`screenOrientation="sensorPortrait"`).

### Auditoria do código em `dev/n64-clean-start`
- A árvore de produção contém `MainActivity.kt`, `N64Bus.kt`, `N64HleBoot.kt`, memória, ROM loader/inspector, CPU R4300 e TLB.
- Não há implementação encontrada de `SurfaceView`, `TextureView`, `GLSurfaceView`, Canvas/Bitmap renderer ou classe de apresentação de quadros.
- Não há núcleo RSP nem RDP no código de produção. O próprio `N64Bus.kt` documenta que a execução RSP não é emulada; o modelo VI atual apenas guarda e lê/escreve registradores.
- VI não gera framebuffer, não faz scanout da RDRAM e não agenda quadros. O teste `N64BusVideoInterfaceTest` valida somente leitura/escrita de registradores.
- Portanto, o app ainda não possui um caminho de vídeo de ponta a ponta. A ausência de imagem não pode ser atribuída apenas à falta de um widget de tela: faltam também os componentes de emulação gráfica que produziriam os pixels.
- O diagnóstico HLE é uma execução limitada de CPU; não é ainda uma sessão contínua de jogo nem uma fonte de quadros.

### Plano técnico ordenado
1. Definir e testar uma interface de framebuffer/quadros entre os dispositivos emulados e a camada Android, sem simular uma imagem de jogo.
2. Implementar a leitura/scanout VI a partir dos registradores reais e da RDRAM, incluindo formatos de pixel e limites de memória; cobrir com testes unitários.
3. Criar uma superfície Android em paisagem para apresentar somente quadros efetivamente produzidos pelo núcleo.
4. Implementar o RDP e a execução RSP necessários para que comandos gráficos reais gerem framebuffer; conectar o resultado ao VI e à superfície.
5. Só então integrar o ciclo contínuo de execução do jogo, áudio e controles.

### Restrições de validação
- Uma superfície Android vazia ou um quadro de teste não prova que GoldenEye está renderizando.
- Não declarar vídeo funcional até demonstrar pixels originados do framebuffer escrito pelo emulador.
- Não declarar GoldenEye iniciado sem evidência de boot real e imagem do jogo.
- Continuar todo o trabalho na branch `dev/n64-clean-start`; não alterar `main`.


## 20. Primeira camada de captura de framebuffer VI — 2026-10-31

- Adicionado `N64VideoFrame`, um contêiner imutável de dimensões, pixels ARGB, origem e formato.
- Adicionado `N64ViFramebufferReader`, que lê pixels reais da RDRAM via `N64AddressSpace`, respeitando origem, largura, altura e bytes por pixel.
- Conversão implementada:
  - VI_STATUS pixel size 2: RGBA5551 big-endian para ARGB8888, incluindo o bit alpha.
  - VI_STATUS pixel size 3: RGBA8888 big-endian para ARGB8888.
- Há validação de largura/altura, formato suportado e limites de RDRAM; a captura não lê além do framebuffer declarado.
- Testes adicionados para vermelho/verde/azul RGBA5551, preservação de canais RGBA8888, formato inválido e framebuffer fora da RDRAM.
- Limitação explícita: esta é uma camada de conversão/captura com dimensões fornecidas pelo chamador. Ainda não calcula altura ativa/temporização VI, não é chamada pelo loop do emulador e não apresenta imagem no Android. Não constitui renderizador RDP/RSP nem prova de vídeo de jogo.
- Próximo passo: integrar a captura aos registradores VI com cálculo de região ativa/escala e, depois, encaminhar quadros válidos a uma superfície Android.

## 21. Captura ligada aos registradores VI — 2026-10-31

- `N64Bus.captureViFrame(height)` conecta a camada `N64ViFramebufferReader` ao estado VI programado pelo software:
  - `VI_STATUS` (offset `0x00`), para o formato de pixel;
  - `VI_ORIGIN` (offset `0x04`), para o endereço inicial;
  - `VI_WIDTH` (offset `0x08`), para o stride/largura.
- A altura permanece parâmetro explícito, pois o cálculo correto de região ativa/temporização ainda não foi implementado. Se VI_WIDTH estiver zerado, a API retorna `null` (nenhum quadro capturável).
- A captura lê a memória RDRAM diretamente, sem usar `read32` do barramento para consultar os registradores e sem gerar eventos MMIO artificiais.
- Teste de integração verifica que os registradores VI selecionam origem/largura/formato, que os pixels reais em RDRAM são convertidos e que a captura não aumenta o histórico MMIO.
- Outro teste verifica ausência de quadro quando VI_WIDTH=0.
- Limitação: ainda não há cálculo automático de altura, chamada no loop de emulação, RSP/RDP nem apresentação Android. Isto valida a conexão VI→leitura de framebuffer, não renderização do GoldenEye.
- CI acionada pelos commits desta etapa deve ser verificada antes de considerar o código validado.

## 22. Altura automática da janela vertical VI — 2026-10-31

- `N64Bus.captureViFrameFromRegisters()` calcula a altura ativa a partir de `VI_V_START` (offset `0x28`) e `VI_Y_SCALE` (offset `0x34`), e então reutiliza a captura dos registradores de origem, largura e formato.
- O campo alto/baixo de `VI_V_START` é decodificado como início/fim da janela vertical (unidades de meia linha). A escala usa 11 bits fracionários, com 2048 representando 1.0; altura = `(fim - início) * escala / 2048` usando divisão inteira truncada. A fórmula de escala é consistente com implementações de vídeo N64 consultadas; não foi adicionada correção empírica de aspecto/overscan.
- Retorna `null` se janela vazia/invertida, escala zero ou altura calculada fora de 1..1024. A API antiga `captureViFrame(height)` continua disponível para diagnóstico controlado.
- Testes cobrem janela de 480 unidades com escala 0.5 (240 linhas), pixels no início do framebuffer, janela vazia e escala zero.
- Referências técnicas: Project64 `Source/Project64-video/Main.cpp` (cálculo a partir de `VI_V_START` e `VI_Y_SCALE`) e N64 Timing Reference (janela vertical em meia-linha). A implementação não inclui o fator corretivo específico de Project64, nem temporização/interlace completo.
- Limitação: cálculo de dimensões não implementa VI scanout por linha, filtros/antialias, sincronização de campo, RSP/RDP ou apresentação Android. Ainda não comprova quadro real de GoldenEye.
- Aguardar CI dos commits abaixo antes de declarar etapa validada.


## 23. Captura VI apresentada na página de diagnóstico — 2026-10-31

Commit de código: `4a24d3503ccc0882442205e79aa91d20affe3512`.

Em `MainActivity.runHleBootDiagnostic()`, após o limite de execução da CPU, a aplicação tenta uma captura única por `bus.captureViFrameFromRegisters()`. O resultado é registrado no log:

- Se não houver configuração VI válida, o relatório informa que nenhum framebuffer foi capturado.
- Se houver frame válido, registra largura, altura, pixel size e origem.
- Se houver erro de formato/faixa de memória, registra o tipo e a mensagem da falha.
- A captura é explicitamente identificada como amostragem única ao final do diagnóstico, não como VBlank, scanout ou temporização VI real.

A página de diagnóstico passou a aceitar um `N64VideoFrame` opcional e, quando presente, converte o array ARGB para `Bitmap` e mostra a imagem acima do log. O botão de copiar logs permanece na mesma página. O caminho antigo que chama `showRomDiagnosticPage(diagnostic)` continua compatível porque o parâmetro de frame é opcional.

Limites importantes:
- O HLE atual ainda é uma execução diagnóstica limitada; não é uma sessão contínua de jogo.
- O VI ainda não possui temporização/VBlank/scanout completo.
- RSP/RDP continuam ausentes; portanto, a captura só exibirá dados se a CPU ou outro código já tiver escrito um framebuffer válido em RDRAM e programado os registradores VI.
- Um frame capturado não comprova boot ou jogabilidade do GoldenEye.
- A conversão para Bitmap e a atualização da interface ocorrem na thread principal ao abrir a página; avaliar custo/performance antes de transformar isso em apresentação contínua.

Próximo passo:
1. Verificar CI para o commit acima.
2. Se passar, adicionar testes para a camada de apresentação/conversão e validar a página em aparelho.
3. Depois projetar uma sessão de emulação contínua com thread própria e sincronização de frames; não reutilizar o loop diagnóstico como game loop.
4. RSP/RDP e temporização VI continuam sendo pré-requisitos para renderizar jogos N64 reais.


## 24. Auditoria da fronteira entre execução, vídeo e interface — 2026-10-31

### Estado confirmado no código após a etapa 23

- `runHleBootDiagnostic()` cria um `R4300Cpu` e executa `cpu.step()` dentro de uma `Thread` dedicada. Portanto, o diagnóstico não bloqueia diretamente a thread da interface durante as instruções.
- A execução continua sendo um procedimento fechado e limitado: máximo de 2.000.000 instruções, coleta de rastros e captura VI única ao final. Não existe uma sessão de jogo reutilizável nem um ciclo de quadros.
- A captura atual ocorre depois do loop CPU, chamando `captureViFrameFromRegisters()`. Não existe callback de frame originado durante a execução nem sincronização com VBlank.
- `showRomDiagnosticPage()` cria `Bitmap` e `ImageView` na thread principal. Isso é aceitável para uma amostra diagnóstica única, mas não deve ser reutilizado como estratégia de apresentação contínua.
- A thread de diagnóstico não é guardada em um controlador de sessão e não há cancelamento cooperativo ligado ao ciclo de vida da Activity. Os botões são reabilitados no término do worker.
- `N64VideoFrame` transporta dimensões, pixels ARGB, origem e formato; a captura e a apresentação ainda estão acopladas ao diagnóstico da Activity, sem uma interface de saída de vídeo do núcleo.

### Decisão de arquitetura

Não converter `runHleBootDiagnostic()` em game loop e não criar um loop infinito de CPU enquanto RSP/RDP/VI timing ainda não existem. Isso produziria apenas execução acelerada de instruções sem a cadência de hardware necessária e poderia consumir bateria/CPU sem entregar vídeo.

A próxima implementação de execução deve ser separada em componentes testáveis:

1. Um controlador/sessão de execução do núcleo, sem dependência Android, com início, parada cooperativa, estado (parado/executando/finalizado/falhou) e execução em lotes limitados de instruções.
2. Um contrato de saída de vídeo que publique apenas frames válidos produzidos pelo núcleo; a camada Android será consumidora e não deverá ler registradores/RDRAM em paralelo com a CPU.
3. Um mecanismo de coalescência/backpressure para não enfileirar Bitmaps nem atualizações da UI mais rápido do que a tela consegue apresentar; manter no máximo o frame mais recente pendente.
4. Um ciclo de vida Android que cancele a sessão ao sair da tela/Activity e encaminhe atualizações de UI pela thread principal.
5. Testes unitários para limites de lote, cancelamento, transições de estado, publicação/coalescência e preservação do diagnóstico atual.

### Pré-requisitos que continuam bloqueando jogo com imagem

- RSP não implementado; RDP não implementado.
- VI ainda não gera scanout temporizado, VBlank/interlace ou quadros periódicos.
- A captura atual apenas interpreta bytes existentes em RDRAM quando os registradores VI parecem válidos. Não cria pixels de cena.
- Portanto, a próxima etapa não deve alegar que GoldenEye está renderizando. O avanço de infraestrutura de sessão deve ser validado com testes sintéticos e permanecer claramente separado de compatibilidade de jogo.

### Build da etapa 23 confirmada

- Build #649, run ID `41222765560`, commit `151c4d282ba72f4c86068bdf6d177c8ed87257fa`.
- Resultado: success; workflow Android build concluído.
- Artifact `n64-android-debug-apk`, ID `13049924251`, 899.104 bytes; SHA-256 `bdf8785ffd93c9700b7440d5a4431c759a2cbe148ce778b753f2cb6f1f2179d8`; expira em 2026-11-14 17:06:01 UTC.
- Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41222765560
- CI verde comprova build/testes automatizados, não validação em aparelho nem boot/renderização do GoldenEye.

### Próxima ação

Projetar e implementar primeiro o controlador de execução independente do Android, com cancelamento e execução em lotes, sem ativá-lo como game loop na interface até existir timing de hardware. Manter toda alteração em `dev/n64-clean-start`; `main` permanece intocada.


## 25. Controlador puro de execução em lotes — 2026-10-31

### Implementação
- Criada a classe `N64ExecutionSession`, sem dependências Android, para fornecer a base de execução controlada do núcleo.
- A sessão é de uso único e recebe um limite positivo de instruções, tamanho positivo de lote e uma função que executa uma instrução.
- Executa no máximo o limite configurado, divide o trabalho em lotes limitados e cede a CPU entre lotes com `Thread.yield()`.
- Cancelamento cooperativo por `requestStop()`; a parada ocorre entre instruções, permitindo que a instrução corrente termine.
- Estados observáveis: READY, RUNNING, STOP_REQUESTED, COMPLETED, CANCELLED e FAILED.
- Snapshot informa estado, quantidade de instruções concluídas e mensagem de falha, quando houver.
- A classe não cria threads, não toca na UI, não acessa CPU/RAM diretamente e não agenda temporização de hardware. O chamador deverá executá-la em worker dedicado.

### Testes
- Conclusão exata no limite de instruções, cruzando múltiplos lotes.
- Cancelamento solicitado durante uma instrução e respeitado antes da próxima.
- Sessão não pode ser executada duas vezes.
- Falha de instrução preserva contagem concluída e marca estado FAILED.
- Limites inválidos são rejeitados.

### Limites e integração
- Esta é uma infraestrutura testável, ainda não conectada ao botão HLE nem ao R4300Cpu.
- Não é game loop, não cria cadência de frames e não substitui RSP/RDP/VI timing.
- A próxima integração deve garantir que CPU, barramento e memória pertençam exclusivamente à sessão enquanto ela roda; diagnósticos paralelos não podem ler/modificar o mesmo estado.
- Antes de ligar a UI, adicionar controlador de ciclo de vida Android com worker dedicado, cancelamento ao sair da tela e publicação/coalescência de frames no thread principal.
- Manter a captura VI diagnóstica como amostragem única até existir scanout e sincronização de hardware.
- Commits: `8e2d1cb7306eaa5741ac35864006e2b589156a94` (classe) e `c321e050d8744689f2e5f06dc61ab07b47398d50` (testes).
- CI deve passar antes de considerar a etapa validada; não alegar teste em aparelho nem renderização de jogo.


## 26. Adaptador do controlador para o R4300 real — 2026-10-31

### Implementação
- Criada `N64CpuExecutionSession`, que compõe `N64ExecutionSession` com a implementação real `R4300Cpu.step()`.
- O adaptador recebe uma CPU existente e encaminha cada instrução para o controlador limitado; expõe `execute()`, `requestStop()` e `snapshot()`.
- O chamador continua responsável por executar `execute()` em worker dedicado e por manter CPU/barramento/memória exclusivos durante a sessão.
- Nenhuma alteração foi feita no fluxo atual de diagnóstico HLE ou nos botões da Activity. A integração de UI fica para depois de uma etapa específica de ciclo de vida/cancelamento.

### Teste
- Novo teste executa duas instruções MIPS reais em `N64Memory` através do adaptador:
  - ADDIU produz `$t0=42`;
  - ADDIU seguinte produz `$t1=43`;
  - PC termina em 8;
  - snapshot informa COMPLETED e duas instruções executadas.
- O teste valida a ligação entre o controlador genérico e a CPU real, não compatibilidade de ROM nem boot de jogo.

### Commits
- `58161eddcb68dc6e35503cae90126628703824ac` — adaptador R4300.
- `3eb0ec56afd4e8c350bac4cb72f0a0513d0027b7` — teste de integração unitário.

### Limites
- Adaptador ainda não é usado pela MainActivity.
- Não há worker Android gerenciado, lifecycle cancellation, sincronização de dispositivos, temporização, RSP/RDP ou publicação de frames.
- Não iniciar loop contínuo de jogo com esta camada isolada.
- Manter `main` sem alterações; todo o trabalho permanece em `dev/n64-clean-start`.


## 27. Proprietário de worker cancelável — 2026-10-31

### Implementação
- Criada `N64ExecutionWorker`, camada sem dependências Android que possui a thread de uma `N64ExecutionSession`.
- `start()` inicia a sessão uma única vez e impede segunda inicialização.
- `requestStop()` encaminha cancelamento cooperativo à sessão; a instrução em andamento pode terminar, e a parada ocorre entre instruções.
- `onComplete` recebe o snapshot final na própria thread worker. Qualquer atualização de UI Android deverá ser encaminhada explicitamente para a main thread pelo chamador.
- Exceções da execução são capturadas pelo worker apenas para permitir a publicação do snapshot; o estado/mensagem FAILED permanecem registrados na sessão.
- `isAlive()` permite consultar se a thread ainda está ativa.

### Testes
- Cancelamento real de worker com limite alto: espera início, solicita parada, aguarda callback e confirma CANCELLED, contagem limitada, thread encerrada e impossibilidade de reiniciar.
- Falha dentro da instrução: confirma callback com estado FAILED e mensagem preservada.

### Limites e próxima integração
- Ainda não conectado à MainActivity nem ao ciclo de vida Android.
- O callback executa na thread worker; não tocar Views diretamente nele.
- O chamador deverá manter CPU/barramento exclusivos, chamar requestStop no encerramento da tela e encaminhar resultados para a main thread.
- Não há pacing de quadros, temporização de hardware, áudio, RSP/RDP ou vídeo contínuo.


## 28. Cancelamento do diagnóstico HLE no ciclo de vida da Activity — 2026-10-31

### Alteração Android
- `MainActivity` agora mantém uma flag `@Volatile hleStopRequested`.
- `onDestroy()` ativa a flag para solicitar parada cooperativa do diagnóstico HLE em execução.
- O loop limitado verifica a flag entre instruções; não interrompe uma instrução no meio.
- Ao detectar cancelamento, o relatório marca o motivo como encerramento da Activity.
- A publicação final da página/log é ignorada se a execução foi cancelada ou se a Activity já está finalizando/destruída.

### Limites
- Esta integração aplica-se ao caminho `runHleBootDiagnostic()`; não altera os diagnósticos legados de IPL3.
- O worker HLE ainda é uma thread local criada pela Activity, não uma instância de `N64ExecutionWorker`. A abstração genérica permanece coberta por testes, mas sua adoção direta exigirá separar a coleta de diagnóstico do loop de CPU.
- Cancelamento é cooperativo, verificado entre instruções; coleta de logs já iniciada pode terminar antes da thread encerrar.
- Não há ainda renderização contínua, frame pacing, RSP/RDP, áudio ou boot funcional comprovado de jogo.


## 29. Observador por instrução no controlador R4300 — 2026-10-31

- `N64ExecutionSession` agora aceita um observador opcional executado depois de cada instrução concluída.
- Se o observador retornar `true`, o controlador solicita cancelamento cooperativo e termina entre instruções.
- `N64CpuExecutionSession` expõe esse ponto de observação com acesso ao `R4300Cpu`, permitindo coletar PC, opcode, registradores e detectar condições de parada sem duplicar o loop de execução.
- Teste novo confirma que o observador vê a primeira instrução, solicita parada e a segunda instrução não é executada.
- Esta extensão prepara a migração do diagnóstico HLE para `N64ExecutionWorker`; a Activity ainda não foi migrada nesta etapa.
- Não muda o limite máximo de instruções, o comportamento padrão quando não há observador, nem implementa timing ou vídeo contínuo.


## 30. Contrato comum entre worker e sessão R4300 — 2026-10-31

- Introduzido `N64ExecutionController`, contrato sem dependência Android para executar, solicitar parada e consultar snapshot.
- `N64ExecutionSession` e `N64CpuExecutionSession` implementam o contrato.
- `N64ExecutionWorker` agora pode possuir tanto a sessão genérica quanto a sessão ligada ao R4300 real.
- Teste novo verifica execução de uma instrução MIPS real por meio de `N64ExecutionWorker`, confirmando callback COMPLETED e registrador esperado.
- Esta etapa fecha a lacuna entre o worker e o adaptador R4300; ainda não migra `MainActivity.runHleBootDiagnostic()`.
- Não acrescenta timing, sincronização de periféricos, RSP/RDP, áudio ou renderização contínua.

## 31. Observadores antes e depois de cada instrução — 2026-10-31

- `N64ExecutionSession` agora aceita callback opcional `beforeInstruction`, executado imediatamente antes de cada instrução, além de `afterInstruction`.
- `N64CpuExecutionSession` disponibiliza ambos os callbacks com acesso ao `R4300Cpu`.
- Teste novo verifica a ordem exata antes/depois em duas instruções MIPS reais e confirma os valores de PC e GPR em cada fronteira.
- Isso permite preservar capturas pré-instrução (PC e registradores anteriores) e pós-instrução (opcode, próximo PC, registradores resultantes) ao migrar o diagnóstico HLE para o worker.
- A `MainActivity` ainda usa seu loop atual nesta etapa; nenhuma alteração de fluxo de diagnóstico ou UI foi feita.
- Sem mudanças em timing, periféricos, RSP/RDP, áudio ou apresentação contínua de vídeo.


## 32. Correção do teste de observadores e validação CI — 2026-10-31

### Diagnóstico da falha anterior
- Builds #675–#679 falhavam em `N64CpuExecutionSessionTest.beforeAndAfterObserversBracketEachRealInstruction`.
- O log do GitHub Actions #679 confirmou `org.junit.ComparisonFailure` na linha 81.
- A segunda instrução sintética estava codificada como ADDIU com destino em `$t1` (rt=9) e fonte em `$t0` (rs=8). O teste verificava incorretamente que `$t0` passaria a 43.
- O comportamento esperado correto é: após a segunda instrução, PC=8, `$t0=42` e `$t1=43`.

### Correção
- Atualizado o teste para registrar também `$t1` nos snapshots antes/depois.
- Expectativa final agora valida explicitamente `after:8:42:43`, preservando a verificação da ordem dos quatro callbacks.
- Commit: `037c023a20b1a7f29a41a8362fb44daebe0ef38f` — `fix: assert destination register written by second ADDIU`.

### Validação
- GitHub Actions #680, run ID `41230306554`: **success**.
- Etapa Build and test: success.
- Etapa Upload debug APK: success.
- [Workflow #680](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41230306554).
- Resultado valida a suíte automatizada e a geração do APK debug; não comprova boot de GoldenEye nem teste em aparelho.

### Estado da integração
- `MainActivity.runHleBootDiagnostic()` ainda usa uma thread local e loop próprio de até 2.000.000 instruções.
- `N64ExecutionWorker` e `N64CpuExecutionSession` ainda não são usados por esse fluxo.
- A migração deve preservar o diagnóstico existente: PC/opcode/próximo PC, janela das últimas 256 instruções, contagem de PCs, observação do laço de cópia, checkpoints, detecção de exceção, snapshots MMIO/periféricos, captura VI única e publicação da UI na main thread.
- A sessão deve ser a única proprietária de CPU/barramento durante execução. O callback do worker ocorre na thread worker e precisa usar `runOnUiThread`; o cancelamento deve ser solicitado em `onDestroy()`.
- Não converter em loop contínuo de jogo: continuam ausentes RSP/RDP e scanout/VBlank temporizado.


## 33. Primeira integração do worker no diagnóstico HLE — 2026-10-31

### Alteração
- `MainActivity` passou a manter uma referência ao `N64ExecutionWorker` do diagnóstico HLE.
- O trabalho HLE é despachado pelo worker dedicado, e falhas de sessão são encaminhadas para a UI por `runOnUiThread`.
- `onDestroy()` agora solicita cancelamento tanto pela flag de diagnóstico existente quanto por `hleWorker.requestStop()`.
- A referência do worker é limpa no callback de conclusão somente se ainda corresponder à execução atual.
- A captura VI, relatório, logs e exibição permanecem no fluxo atual; a tela não foi redesenhada.

### Limitação importante desta etapa
- Esta é uma integração intermediária de propriedade da thread/lifecycle: o controlador genérico envolve o trabalho HLE existente como uma tarefa limitada de uma unidade.
- **O loop de CPU de até 2.000.000 instruções ainda permanece dentro da tarefa e ainda verifica a flag `hleStopRequested`; ele ainda não foi migrado para `N64CpuExecutionSession` com callbacks antes/depois de cada instrução.**
- Portanto, não declarar concluída a migração do loop de CPU. O próximo passo é transferir a lógica por instrução para os observadores da sessão R4300, preservando os contadores e rastros.
- O worker callback continua sendo thread de background; qualquer acesso a Views precisa permanecer dentro de `runOnUiThread`.
- Não há game loop, temporização, RSP/RDP ou scanout de vídeo contínuo.

### Commits
- `f1ca0307aed4efb9a133bc1b517221142a8b29a3` — primeira integração do worker no diagnóstico.
- `7766d2c896931c0c4cc521c20dd07a65552cc310` — correção de inicialização da referência capturada no callback.

### Validação
- CI iniciado após as alterações; registrar resultado do workflow antes de considerar esta etapa validada.


### Correção adicional da etapa 33
- O primeiro build da integração apontou `Unresolved reference 'workerRef'`: a variável local era capturada no callback antes da declaração.
- Removida a comparação com essa referência local; o botão HLE fica desabilitado durante a execução, portanto há apenas uma execução ativa pelo fluxo normal da tela. O callback limpa `hleWorker`.
- Commit de correção: `131310f163c65cd1e4f0090e531436481c8e23e3`.
- O CI da correção está em andamento; a integração só será considerada compilada após resultado verde.


## 34. Migração do loop HLE para N64CpuExecutionSession — 2026-10-31

### Alteração de código
- O loop manual `while (steps < maxSteps)` em `MainActivity.runHleBootDiagnostic()` foi substituído por `N64CpuExecutionSession` com limite de 2.000.000 instruções e lotes de 512.
- `beforeInstruction` agora captura PC, estado dos registradores para o laço de cópia e atualiza `bus.setDiagnosticCpuPc()` imediatamente antes de cada instrução.
- `afterInstruction` registra opcode/PC seguinte, ring buffer de 256 instruções, contagem dos PCs, trace do laço de cópia, amostras, pós-saída, primeiras 48 instruções e checkpoints de 50.000; também interrompe em exceção CPU ou quando `hleStopRequested` fica ativo.
- A contagem final passa a usar `executionSnapshot.executedInstructions`, evitando depender somente do contador local.
- A captura VI única e o relatório de dispositivos/MMIO permanecem após a execução da sessão.

### Arquitetura e ressalvas
- A Activity ainda usa uma sessão genérica externa de uma tarefa para manter o diagnóstico completo no worker existente; dentro dessa tarefa, a execução das instruções R4300 agora é feita por `N64CpuExecutionSession`.
- O cancelamento de fechamento é cooperativo: `onDestroy()` ativa `hleStopRequested`, verificado no observador pós-instrução. A solicitação de parada do worker externo também é mantida.
- A arquitetura pode ser simplificada futuramente para o próprio worker receber diretamente um controlador composto que execute preparação HLE, sessão CPU e relatório, sem sessão externa de tarefa.
- Nenhuma implementação RSP/RDP, VBlank, scanout contínuo, temporização de hardware ou boot real de GoldenEye foi adicionada.

### Commit e validação
- Commit: `1c288cfbdadd9a9381249c0a77f9b3395828673f`.
- GitHub Actions #687 (run ID `41231769395`) iniciou; resultado deve ser conferido antes de considerar a etapa validada.


## 35. Finalização do worker HLE na thread principal — 2026-10-31

### Revisão
- Builds #687 (código da sessão R4300) e #688 (documentação) concluídas com sucesso.
- Na revisão do lifecycle foi observado que o callback final de `N64ExecutionWorker` limpava `hleWorker` diretamente na thread de background; além disso, apenas o estado FAILED tinha um caminho explícito de recuperação dos botões.

### Correção
- O callback de conclusão agora encaminha toda a finalização da Activity para `runOnUiThread`.
- A referência é limpa somente se ainda corresponder à instância que concluiu (`workerRef`).
- Estados FAILED e CANCELLED reabilitam os botões se a Activity ainda estiver ativa; FAILED também apresenta a mensagem de erro.
- Estado COMPLETED mantém a publicação normal do relatório, que já é agendada na thread principal pela tarefa diagnóstica.
- Commit: `4fee619e4eaf9887e7f9712f99bba4dc8756c9f6`.

### Validação pendente
- GitHub Actions #689, run ID `41232115523`, acionada para validar a alteração.
- Não considerar esta correção validada até Build and test e Upload debug APK concluírem com sucesso.

### Limites preservados
- Não altera a lógica do R4300, o handoff HLE, a captura VI ou o formato do relatório.
- Não implementa game loop, RSP, RDP, VBlank, áudio, controles ou boot real de GoldenEye.


## 36. Primeira extração do diagnóstico HLE para componente sem UI — 2026-10-31

### Alteração
- Criado `N64HleDiagnosticRunner`, inicialmente responsável pela etapa de preparação do handoff HLE e pela criação das seis linhas de resumo inicial.
- `MainActivity.runHleBootDiagnostic()` agora delega essa preparação ao runner e incorpora as linhas retornadas ao relatório existente.
- O runner não depende de APIs Android e não executa IPL3/PIF ROM.
- O objeto de preparação mantém o resultado tipado de `N64HleBoot.prepare()` junto com o resumo para que as próximas etapas possam mover execução e coleta de diagnóstico sem alterar o contrato da UI.

### Testes
- Criado `N64HleDiagnosticRunnerTest` com ROM sintética CIC 6102/7101.
- O teste verifica o entry point, tamanho do payload, PC preparado e conteúdo do resumo.
- O teste também confirma que o resumo identifica explicitamente que IPL3 não foi executado.

### Limite desta extração
- Esta é a primeira fatia da refatoração; rastreamento por instrução, relatório completo, captura VI e snapshots do barramento ainda permanecem em `MainActivity`.
- O worker ainda usa o controlador externo de uma tarefa; sua substituição por um controlador composto será feita após a extração do relatório e da execução.
- Não há implementação RSP/RDP, temporização VI/VBlank, áudio ou boot comprovado de GoldenEye.

### Commits
- `a0e503139731bec2e78da5367d7cf2fe11541c05` — extrai a preparação HLE.
- `ce473cae1f39c915fc92ab193d03a7f66d423530` — Activity delega preparação ao runner.
- `495a02206a4fb45f32ffe8e270873aa2c9124d39` — teste do resumo e handoff.

### Validação
- CI deve concluir com Build and test e geração do APK debug antes de considerar esta etapa validada.


## 37. Contrato estruturado de resultado do diagnóstico HLE — 2026-10-31

### Alteração
- Criado `N64HleDiagnosticResult`, modelo sem dependência Android para transportar linhas do relatório, frame VI opcional, quantidade de instruções, motivo de parada e mensagem de falha opcional.
- A publicação do diagnóstico na Activity agora monta esse resultado e apresenta suas linhas/frame na UI principal.
- A criação de `Bitmap` e a atualização de Views continuam na Activity; o resultado carrega somente os pixels decodificados de `N64VideoFrame`.
- O modelo valida contagem não negativa de instruções e motivo de parada não vazio.

### Testes
- Criado `N64HleDiagnosticResultTest` para validar os campos, frame opcional e rejeição de contagem negativa.

### Limite desta etapa
- A execução da CPU, os callbacks de rastreamento e a montagem detalhada do relatório ainda permanecem em `MainActivity`; esta etapa estabelece o contrato de saída para a extração seguinte.
- O worker externo ainda envolve o diagnóstico como tarefa genérica de uma unidade.
- Não adiciona RSP/RDP, VBlank/scanout contínuo, áudio, controles ou boot comprovado de GoldenEye.

### Commits
- `7896de242597b1d328b75ffb53696a5d05f874aa` — cria o modelo de resultado.
- `83a2866387a69219afc5bb3d3a2d6be11f556e6b` — Activity publica o resultado estruturado.
- `57d30b2f4eeb7271792307a5f030269131029878` — adiciona testes do contrato.

### Validação
- Aguardar Build and test e Upload debug APK do workflow acionado após os commits.


## 38. Preservação de falhas no resultado HLE — 2026-10-31

### Alteração
- A execução HLE agora guarda a classe e a mensagem da exceção capturada no fluxo principal de diagnóstico.
- O campo `failureMessage` de `N64HleDiagnosticResult` é preenchido quando a coleta/execução lança uma exceção; permanece nulo quando não houve exceção capturada.
- O relatório textual de contexto preservado continua sendo montado como antes, e o resultado estruturado passa a transportar também o detalhe técnico para consumidores futuros.

### Teste
- `N64HleDiagnosticResultTest` agora valida a preservação de uma mensagem de falha no modelo.

### Limite
- Falhas de CPU tratadas como exceções arquiteturais pela própria sessão continuam distintas de exceções Kotlin lançadas no fluxo externo.
- O runner ainda não assumiu o loop completo nem a coleta dos snapshots. Esta correção melhora o contrato antes da extração.
- Não implementa RSP/RDP, scanout temporizado ou boot comprovado de GoldenEye.

### Commits
- `d51ebc991d2127180355b9255c24ee06cb3a3317` — captura mensagem de exceção no resultado.
- `5a0611fbdc9d0f99df7b6d74bc4e21e789ec8c8e` — teste da mensagem de falha.

### Validação
- Aguardar CI de código e documentação.


## 39. Integração da execução R4300 pelo runner — 2026-10-31

### Alteração
- `MainActivity.runHleBootDiagnostic()` agora chama `N64HleDiagnosticRunner.executeCpu()` em vez de instanciar diretamente `N64CpuExecutionSession`.
- O runner passou a ser o ponto responsável por criar/executar a sessão R4300 limitada e devolver seu snapshot.
- Os callbacks antes/depois da instrução continuam definidos na Activity nesta etapa, preservando os traces e checkpoints existentes.
- A captura VI, o relatório detalhado e a apresentação Android continuam no fluxo anterior; nenhuma alteração de renderização foi feita.

### Teste e validação
- Teste sintético em `N64HleDiagnosticRunnerTest` cobre duas instruções ADDIU, estado COMPLETED, contagem, PC, registradores e ordem dos callbacks.
- Commits: `c8746bbb1e7bb91ea9e310431d28bf854d8730e2` (runner), `892d9da27539701752db1c3a66c2cdcf9086f0be` (teste) e `5841a340a56983920c2a9bf3439a04a1ebcfcdaa` (integração Activity).
- CI #699 (runner), #700 (preservação de falha) e #701 (teste de execução) concluíram com sucesso. CI #702 e #703 ainda devem ser conferidas após a conclusão.

### Limites
- A coleta dos callbacks/traces, montagem do relatório e snapshots MMIO ainda permanecem em `MainActivity`; próxima etapa é mover essa coleta para um componente puro sem mudar o conteúdo do diagnóstico.
- O worker ainda envolve a tarefa completa em uma sessão genérica externa de uma unidade.
- Não foram implementados RSP/RDP, timing VI/VBlank, áudio, controles ou boot comprovado de GoldenEye.
- `main` permanece intacta.

## 40. Coletor limitado de rastros de instruções HLE — 2026-10-31

### Alteração
- Criado `N64HleInstructionTrace`, componente Kotlin sem dependência Android para armazenar a janela recente de instruções e contadores de visitas por PC.
- A janela recente tem limite configurável (padrão 256); o mapa de PCs distintos também é limitado (padrão 4096), mas continua contando visitas para PCs já registrados.
- O snapshot fornece as linhas recentes, os PCs mais visitados e a quantidade de PCs contabilizados.
- O componente não interpreta instruções nem afirma que um endereço representa uma rotina específica.

### Testes
- `N64HleInstructionTraceTest` cobre descarte das instruções antigas, formatação de instrução ausente, contagem repetida, limite de PCs distintos e rejeição de limites inválidos.

### Limite desta etapa
- O coletor foi criado e testado isoladamente, mas **ainda não substitui** as estruturas locais equivalentes de `MainActivity`. A integração será feita em etapa seguinte, preservando o formato dos logs.
- A Activity ainda concentra os callbacks de instrução, traces específicos do laço de cópia, checkpoints, snapshots do barramento e montagem do relatório.
- O worker externo ainda envolve o diagnóstico como uma tarefa genérica; a arquitetura composta continua pendente.
- Nenhuma mudança em RSP, RDP, temporização VI/VBlank, áudio, controles ou boot real de GoldenEye.

### Commits
- `0a699397f57b543c822af9b7166902227dea3aac` — adiciona o coletor.
- `f8cf8ac8b24139289f3cc7c1475580ba9ff98f10` — adiciona testes.

### Validação
- Build #707, run ID `41234995195`, commit `f8cf8ac8b24139289f3cc7c1475580ba9ff98f10`: **success**.
- Build and test: success.
- Upload debug APK: success.
- [Workflow #707](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41234995195).
- A build valida os testes automatizados e a geração do APK debug; não comprova execução ou renderização de GoldenEye no aparelho.


## 41. Auditoria e atualização do documento de continuidade — 2026-10-31

### O que foi auditado
- Documento `docs/CONTINUIDADE_CHAT.md` na branch `dev/n64-clean-start`.
- Histórico de commits da branch e estado do HEAD.
- Resultados recentes do GitHub Actions.
- Implementação de `N64HleInstructionTrace.kt`, testes e rastreamento ainda presente em `MainActivity.kt`.

### Achados
- O documento continha 40 etapas úteis, mas o cabeçalho ainda apontava a documentação como pendente após a etapa 32 e citava a build #680 como mais recente.
- A seção de estado atual ainda apresentava o commit `0ea295f...` como se fosse o estado vigente.
- As seções de próximos passos e mensagem para o próximo chat ainda orientavam consultar/instalar a build #592.
- A validação da etapa 40 estava escrita como pendente, embora o CI #707 já tivesse concluído com sucesso.
- O coletor `N64HleInstructionTrace` e seus testes existem, mas o coletor não está integrado à `MainActivity`. A Activity ainda mantém estruturas locais de contagem de PCs e buffer/resumo. Isso foi mantido como próximo passo, não como trabalho concluído.

### Estado Git/CI verificado
- HEAD da branch `dev/n64-clean-start`: `fcce62293ce65b871bcf04d20894b7da9f2cd27a`.
- Commit de código anterior ao registro documental: `f8cf8ac8b24139289f3cc7c1475580ba9ff98f10`.
- Build #707 / run ID `41234995195`: sucesso para o commit de código.
- Build #708 / run ID `41235044776`: sucesso para o commit de documentação.
- `main` não foi modificada nesta auditoria.

### Alterações documentais desta etapa
- Corrigidos os campos de último commit, HEAD e CI no cabeçalho.
- Identificado explicitamente o commit `0ea295f...` como histórico, não atual.
- Marcada a descrição de 500.000 instruções como histórica; o limite atual documentado é 2.000.000.
- Substituídas as instruções obsoletas das seções 8 e 9 por estado atual, próximos passos e mensagem de arranque para o próximo chat.
- Atualizado o histórico e o resultado de validação da etapa 40.
- Preservadas as 40 etapas anteriores e suas evidências; nenhuma implementação de código foi alterada nesta auditoria.

### Próxima ação
Integrar cuidadosamente `N64HleInstructionTrace` à `MainActivity`, após nova inspeção do HEAD e dos testes. Preservar o comportamento e os campos de diagnóstico existentes. Registrar o commit e o CI dessa integração como etapa 42, somente depois de confirmação real.


## 42. Integração do coletor limitado de rastros HLE à MainActivity — 2026-10-31

### Objetivo
Eliminar a duplicação da janela recente de instruções e da contagem limitada de PCs dentro do fluxo HLE principal, passando a usar o componente puro `N64HleInstructionTrace` criado na etapa 40.

### Alteração realizada
- Arquivo alterado: `app/src/main/java/com/jsr/n64android/MainActivity.kt`.
- Instanciado `N64HleInstructionTrace` no escopo do diagnóstico HLE principal.
- O callback `recordExecution` agora delega o registro de PC, opcode e próximo PC ao coletor reutilizável.
- O relatório normal agora obtém a janela recente, PCs mais visitados e total de PCs distintos por meio de `snapshot()`.
- O caminho de exceção/falha também inclui snapshot das instruções recentes e PCs mais visitados antes da falha, ampliando a evidência preservada.
- Removidas do fluxo HLE principal as estruturas locais equivalentes para a janela de 256 instruções e mapa de até 4096 PCs.
- Os traces especializados do laço de cópia, amostras periódicas, 64 instruções após a saída, checkpoints, snapshots MMIO, DMA, RDRAM, VI e relatório de registradores foram preservados.
- O método legado separado continua tendo estruturas próprias; esta etapa integra somente o fluxo `runHleBootDiagnostic()`, sem alterar o diagnóstico legado.

### Validação
- Commit de código: `3e88607b7011630bc9063f2aa1059cfb2c8563a8`.
- Diff: 14 adições e 18 remoções em `MainActivity.kt`.
- Testes unitários existentes de `N64HleInstructionTraceTest` continuam sendo a cobertura do componente: janela recente, contagem repetida, limite de PCs e limites inválidos.
- CI #711, run ID `41250019688`, estava em andamento no momento deste registro. Não declarar build/testes aprovados até a conclusão.
- Nenhum APK foi instalado ou testado no aparelho nesta etapa.
- Nenhuma alteração em `main`.

### Próximo passo
1. Confirmar o resultado completo da build #711, incluindo Build and test e Upload debug APK.
2. Se o CI passar, considerar a integração estrutural validada e atualizar este registro com o resultado.
3. Em seguida, avaliar extração do relatório HLE para um componente dedicado, mas somente depois de inspecionar dependências e preservar a ordem e o conteúdo dos logs.
4. Não avançar para RSP/RDP ou alegar renderização funcional com base nesta refatoração.

## 43. Extração do rastreador do laço de cópia HLE — 2026-10-31

### Objetivo
Retirar de `MainActivity.runHleBootDiagnostic()` o estado e a formatação dos traces especializados do laço de cópia, mantendo a Activity responsável pela sessão, CPU, barramento e publicação do relatório.

### Implementação
- Criado `N64HleCopyLoopTrace.kt`, componente Kotlin sem dependências Android.
- O componente recebe observações antes/depois de cada instrução e recebe snapshots de registradores por callbacks lazy, evitando formatar registradores quando nenhuma linha será armazenada.
- Armazena contagem de iterações, desvios que retornam ao início, observação da saída, até 32 linhas detalhadas, amostras das primeiras 8 iterações e depois a cada 4096, e até 64 linhas a partir da instrução de saída.
- `MainActivity.runHleBootDiagnostic()` agora delega a coleta a esse componente e monta o mesmo bloco do relatório na mesma posição e ordem.
- A janela geral de 256 instruções, checkpoints de 50.000, primeiras 48 instruções, captura VI única, snapshots MMIO/periféricos e tratamento de falhas foram mantidos.
- O diagnóstico legado `runIpl3HandoffDiagnosticLegacy()` não foi alterado.

### Testes e correção encontrada
- Criado `N64HleCopyLoopTraceTest.kt` com testes de contagem, desvio/saída, janela de 64 instruções, limite de 32 linhas, amostras iniciais/periódicas e ausência de snapshots de registradores fora das regiões retidas.
- As builds intermediárias #715 (run ID `41250249388`) e #716 (run ID `41250267431`) falharam porque o teste de amostragem simulava o PC de início do intervalo em vez do PC da instrução de store que incrementa o contador.
- O teste foi corrigido no commit `81f64a0190a993fc9917995f6792afc5557e349e`, usando `LOOP_STORE_PC` para simular a iteração real.
- Build #717 (run ID `41250293247`) concluiu com **success**; Build and test e Upload debug APK passaram. O workflow validou o HEAD com a correção e a integração completa.
- Artifact: `n64-android-debug-apk`, ID `13060674754`, tamanho 914.918 bytes, expiração 2026-11-14 22:56:08 UTC. Confirmar validade antes de tentar baixar.
- [Workflow #714 — compilação do componente](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250243896).
- [Workflow #715 — teste inicial com fixture incorreta](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250249388).
- [Workflow #716 — integração com fixture incorreta](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250267431).
- [Workflow #717 — código/testes integrados corrigidos](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41250293247).
- Nenhum APK foi instalado ou testado no aparelho nesta etapa.
- Nenhuma alteração em `main`.

### Próximo passo
1. Confirmar CI #718 para esta atualização documental.
2. Se logs do aparelho forem enviados, comparar as seções do laço com o formato anterior; corrigir qualquer divergência real com teste de regressão.
3. Em seguida, considerar extrair os checkpoints e as primeiras 48 instruções para o runner, mantendo limites e ordem.
4. Não confundir CI verde com boot ou renderização funcional do GoldenEye.

## 11. Etapa 44 — primeiro teste visual sintético do VI (2026-10-31)

### Objetivo

Criar um caminho de teste independente de ROM para verificar a sequência RDRAM -> registradores VI -> leitor de framebuffer -> Bitmap/ImageView Android. Isso permite testar a apresentação de pixels no aparelho antes de depender de CPU/RSP/RDP ou de um jogo.

### Implementação enviada

Arquivos:
- app/src/main/java/com/jsr/n64android/N64VideoTestPattern.kt — cria uma RDRAM sintética, preenche 320x240 pixels RGBA5551 com oito barras de cores e programa VI_STATUS, VI_ORIGIN, VI_WIDTH, VI_V_START e VI_Y_SCALE. Em seguida chama captureViFrameFromRegisters() para obter o mesmo tipo de frame utilizado no diagnóstico de ROM.
- app/src/test/java/com/jsr/n64android/N64VideoTestPatternTest.kt — verifica dimensões 320x240, origem, pixelSize e as oito cores convertidas, incluindo o último pixel.
- app/src/main/java/com/jsr/n64android/MainActivity.kt — adiciona ao menu portrait o botão “Testar vídeo (padrão de cores)”. Ao tocar, gera o frame sintético e mostra-o em um ImageView, com detalhes do framebuffer e botão de retorno ao menu. A tela informa explicitamente que o teste não representa renderização de jogo.

Commits da etapa 44:
- 4cc521253380c5b697669161277944088bdb7e68 — gerador de padrão de cores e captura via VI.
- bcadd7aced60edcbd24b2e10969ba920d3cb34f1 — teste unitário do padrão.
- e187a2757551ac000ccc5a4b9e4b244b36dc02c4 — botão e tela de teste visual Android.

### O que este teste comprova — e o que não comprova

Se os testes automatizados passarem, eles comprovam que o gerador grava pixels RGBA5551 em memória sintética, programa os registradores VI modelados, calcula a altura pela janela vertical/escala e recupera os pixels esperados pelo leitor atual. Somente abrir a opção em um aparelho e ver as barras comprova a apresentação visual no Android.

Não comprova:
- execução do RSP;
- rasterização RDP ou comandos gráficos reais do N64;
- VBlank/scanout temporizado;
- captura de framebuffer produzido por um jogo;
- compatibilidade ou renderização do GoldenEye 007.

### Estado de validação

- CI #719 (run ID 41250393284) confirmou com sucesso a etapa documental anterior, com Build and test e Upload debug APK.
- CI #720 (run ID 41250932243), #721 (run ID 41250934832) e #722 (run ID 41250938272) concluíram com success. Em todos, Build and test e Upload debug APK passaram.
- Artifact da build #722: n64-android-debug-apk, ID 13061057670, 916.802 bytes, não expirado na consulta; expira em 2026-11-14 23:05:29 UTC.
- A etapa 44 está validada por CI para compilação e testes automatizados. O workflow documental #723 (run ID 41251008024) ainda estava em execução na última consulta.
- Próxima ação prática: disponibilizar/baixar o APK da build #722, instalar no Android e pedir ao usuário para abrir “Testar vídeo (padrão de cores)”. Não presumir instalação ou resultado visual sem confirmação do usuário.

### Próximo passo

1. Confirmar CI #720, #721 e #722 e corrigir qualquer falha real antes de prosseguir.
2. Atualizar este documento com o resultado da CI documental subsequente.
3. Entregar o APK debug mais recente e orientar o teste no aparelho.
4. Resultado: usuário confirmou a tela de teste no aparelho. A primeira apresentação de um frame sintético pela rota VI/Android está confirmada; ainda não há evidência enviada de inspeção pixel a pixel das oito barras.
5. Próxima etapa: evoluir para um framebuffer alimentado por um produtor gráfico controlado e, mais tarde, para comandos RDP reais. Não chamar esse estágio de emulação completa de vídeo.

- 2026-10-31 — etapa 44: adicionado gerador de framebuffer sintético 320x240 RGBA5551, captura pelos registradores VI e botão de teste visual independente de ROM no menu. Commits 4cc5212, bcadd7a e e187a27; CI #720–#722 pendentes na última consulta.

- 2026-10-31 — validação da etapa 44: CI #720, #721 e #722 concluídos com sucesso (Build and test e Upload debug APK); artifact #722 ID 13061057670. CI documental #723 aguardava conclusão.

- 2026-10-31 — CI documental #723 (run ID 41251008024) concluiu com success, validando o registro de aprovação da etapa 44.

- 2026-10-31 — teste no aparelho confirmado pelo usuário: tela “TESTE DE VÍDEO”, framebuffer 320x240, RGBA5551, origem 0x1000; o usuário confirmou que a tela com a mensagem de frame sintético lido pelo VI e exibido pelo Android apareceu. Ainda não foi enviado screenshot da área da imagem para confirmar cada barra de cor.


## 12. Etapa 45 — primeiro processador isolado de preenchimento RDP (2026-10-31)

### Objetivo
Começar a substituir a geração artificial de pixels por operações gráficas determinísticas que escrevem diretamente em RDRAM. Esta etapa cria um subconjunto pequeno e isolado do RDP, sem fingir que a lista de comandos já vem do RSP/DPC.

### Implementação
- Criado `app/src/main/java/com/jsr/n64android/N64RdpFillProcessor.kt`.
- O processador interpreta um subconjunto de comandos RDP:
  - `SetOtherModes`: identifica o modo de ciclo e exige fill-cycle para `FillRectangle`.
  - `SetColorImage`: configura imagem RGBA de 16 ou 32 bits, largura e endereço de destino em RDRAM.
  - `SetScissor`: limita a região de desenho.
  - `SetFillColor`: configura a cor de preenchimento.
  - `FillRectangle`: grava pixels no endereço de imagem configurado, respeitando recorte e limites de RDRAM.
- Criado `app/src/test/java/com/jsr/n64android/N64RdpFillProcessorTest.kt`, com cenários para escrita RGBA5551, retângulo, scissor e exigência de fill-cycle.
- O componente usa a interface `N64AddressSpace` existente e não depende de Android.

### Limites explícitos
- Ainda não está ligado ao RSP, ao registrador DPC ou à fila de comandos de um jogo.
- Não implementa triângulos, texturas/TMEM, combinação de pixels, Z-buffer, blending, anti-aliasing, sincronização ou rasterização RDP completa.
- Não altera a captura VI nem a tela Android existente.
- Portanto, não comprova renderização de GoldenEye 007 nem de qualquer ROM.

### Commits da etapa
- `a4c2682938ce478e6b1c9dd6c62c89cc93ee12c0` — processador isolado de fill-cycle.
- `a1cc226045e6af4a8d611368a738219b63f34284` — testes iniciais.
- `cd217b10ba66bbb44c1ceef28d48e77bb54abc72` — ajuste da cor repetida no cenário de scissor.
- `a38a468c6b53a33db28d51410ea7e49fc411aaef` — literal Long explícito no teste do modo de ciclo.

### CI
- Build #726 (run ID `41251510492`): **success**, documentação da etapa 44.
- Build #727 (run ID `41251679635`): **success**, compilação e testes existentes do processador isolado, antes da inclusão do teste dedicado.
- Build #728 (run ID `41251681769`): falhou no passo Build and test durante a primeira versão do teste; foi seguido por ajustes e nova execução.
- Build #729 (run ID `41251700924`): **success**, Build and test e Upload debug APK para o teste corrigido no commit `cd217b1...`.
- Build #730 (run ID `41251747014`): valida o commit final `a38a468...`; estava em execução durante a atualização deste documento. Não declarar esta revisão final validada até conferir seu resultado.
- Nenhum APK desta etapa foi confirmado como instalado ou testado no aparelho.
- Nenhuma alteração em `main`.

### Próxima etapa
1. Confirmar Build #730 para o commit final e atualizar este registro com o resultado.
2. Projetar testes de borda para alinhamento, limites de framebuffer e coordenadas de scissor antes de ampliar comandos.
3. Depois, avaliar integração explícita com DPC/RSP somente quando houver modelo de fila e estado de execução adequados; não inventar uma fonte de comandos.
4. Manter o caminho sintético de vídeo como teste separado até que um produtor RDP validado possa gerar o framebuffer.



## 13. Revisão corretiva do processador RDP — 2026-10-31

Uma revisão do código após a etapa 45 encontrou um erro na extração do campo `cycle_type` do comando `SetOtherModes`: a implementação lia os bits 21:20 da palavra baixa, mas o campo pertence à palavra alta do comando RDP (bits globais 53:52). O teste inicial repetia a codificação incorreta e, por isso, não detectava o problema.

### Correção aplicada
- `N64RdpFillProcessor.kt`: agora extrai o ciclo por `(command ushr 52) and 0x3`.
- `N64RdpFillProcessorTest.kt`: os comandos de teste agora codificam `cycle_type` na palavra alta, nas posições corretas.
- Acrescentados testes de escrita de pixel RGBA8888 (32 bits) e proteção contra escrita além do fim da RDRAM.
- O teste de limite foi ajustado para endereçar um pixel realmente fora da memória (base `0xFC`, largura 4, pixel X=2 em RDRAM de 256 bytes).

### Commits corretivos
- `54cfe2dda9b24f8a2ea26a4189f0946c1b8af9b0` — correção da leitura do cycle type.
- `64f6d1683d5a235645e11cc8b34799ae31e3ba63` — atualização da codificação do comando nos testes.
- `98ddd823ba9a9d2f0fe567b8d4b292c64ac18746` — testes RGBA8888 e limite de RDRAM.
- `2ac17d48f1de690a82c63339dce5d732d4725472` — correção das coordenadas do teste de limite.

### CI corretiva
- #732 run `41252114386`: commit de correção do processador; execução iniciada.
- #733 run `41252117623`: atualização dos testes; execução iniciada.
- #734 run `41252139530`: testes adicionais; execução iniciada.
- #735 run `41252153682`: coordenadas corrigidas; execução iniciada.
- Conferir o resultado do workflow para o HEAD atual antes de considerar esta revisão validada. Builds de commits anteriores não substituem a validação do commit mais recente.

### Próximo passo
- Aguardar a CI do HEAD final e corrigir eventuais falhas.
- Fazer uma revisão técnica de coordenadas inclusivas/exclusivas, scissor, alinhamento e limites da imagem antes de ampliar o subconjunto.
- Só depois desenhar a ligação a DPC/RSP, com estado e semântica reais. O processador continua isolado e não renderiza ROMs.

## 14. Estado de continuidade atualizado — etapa 46 (2026-10-31)

> **Este bloco é o estado mais recente e prevalece sobre metadados antigos e próximos passos históricos das seções anteriores.** Antes de continuar, confira o HEAD e o Actions na branch. Não presuma que os números de CI abaixo já terminaram.

### Estado confirmado no GitHub
- Repositório: `jovemaprendz55-byte/N64-Android`.
- Branch de trabalho: `dev/n64-clean-start`.
- HEAD confirmado nesta atualização: `a5c0a547b500d30553b0377c41c264ed2bdb88c0`.
- A branch `main` não foi modificada.
- O trabalho está na primeira ligação experimental entre lista de comandos em RDRAM e o processador RDP isolado.
- CI do commit atual #739 (run ID `41254029960`) estava **in_progress** na consulta. Os workflows #737 (run ID `41254009813`) e #738 (run ID `41254018347`) também estavam em andamento para commits anteriores. Não declarar o HEAD validado até consultar especificamente o #739 e seus jobs.

### Auditoria que orientou a etapa 46
A árvore da branch foi listada integralmente. Não existe ainda arquivo/classe dedicado ao DPC; os arquivos Kotlin existentes incluem `N64Bus.kt`, `N64RdpFillProcessor.kt`, `N64VideoFrame.kt`, `N64HleBoot.kt`, `R4300Cpu.kt`, `R4300Tlb.kt`, sessões/worker de CPU e testes associados.

Constatações:
- `N64Bus.kt` implementa RDRAM e vários blocos MMIO parciais (SP, MI, PI, SI, AI, VI, RI), mas não implementa os registradores DPC em `0x04100000`–`0x0410001C`.
- `N64Bus.mmioDevice()` não classifica endereços DPC.
- `N64Bus.read32()/write32()` não têm tratamento de registradores DPC.
- `N64RdpFillProcessor.execute(commands)` recebe diretamente uma lista Kotlin de palavras de 64 bits; não lê uma display list de RDRAM e não está ligado ao DPC/RSP.
- O RDP atual entende somente o subconjunto já documentado na seção 12: SetOtherModes, SetColorImage, SetScissor, SetFillColor e FillRectangle, com restrições.
- `N64Bus.captureViFrameFromRegisters()` lê framebuffer da RDRAM pelos registradores VI, mas a captura no diagnóstico HLE é única e ocorre após a janela de execução; não é scanout/VBlank temporizado.
- O teste de vídeo sintético exibido no aparelho pelo usuário confirma apenas a rota sintética VI/Android, não renderização de ROM.
- O BOOT HLE prepara o handoff da CPU e usa o barramento; não deve ser transformado no próprio rasterizador. A futura máquina integrada deve compartilhar a mesma RDRAM/barramento entre CPU, DPC/RDP e VI.

### Referência técnica usada para planejar o DPC
- SGI/Nintendo, *Nintendo 64 RSP Programmer's Guide*, seção “Controlling the RDP”: descreve a FIFO de comandos, leitura em unidades de 8 bytes, avanço de CURRENT até END e atualização bufferizada de START/END. PDF: https://ultra64.ca/files/documentation/silicon-graphics/SGI_Nintendo_64_RSP_Programmers_Guide.pdf
- libdragon `rdp.h`: define DP_START, DP_END, DP_CURRENT e DP_STATUS nos offsets `0xA4100000`, `+4`, `+8`, `+0xC` no segmento KSEG1 (equivalentes físicos `0x04100000`–`0x0410000C`), e os bits documentados de status. Fonte: https://github.com/DragonMinded/libdragon/blob/trunk/include/rdp.h
- Estas fontes orientam a implementação futura dos registradores. A classe adicionada nesta etapa ainda não modela a FIFO física, START/END double-buffered, XBUS/DMEM, temporização ou interrupções.

### Etapa 46 — ponte experimental DPC command-list → RDP
Arquivos criados:
- `app/src/main/java/com/jsr/n64android/N64DpcCommandProcessor.kt`
- `app/src/test/java/com/jsr/n64android/N64DpcCommandProcessorTest.kt`

Comportamento atual:
- Recebe `N64AddressSpace`, tamanho declarado da RDRAM e instância de `N64RdpFillProcessor`.
- Lê o intervalo `[startAddress, endAddressExclusive)` da RDRAM, com END exclusivo.
- Exige START e END alinhados em 8 bytes e dentro da RDRAM.
- Lê cada comando como dois `read32` big-endian e combina em uma palavra de 64 bits.
- Impõe limite de 1 MiB por chamada (configurável) para evitar leitura/execução sem limite.
- Entrega a lista ao processador RDP já existente e devolve contagem de comandos, retângulos e pixels escritos.
- Teste principal grava quatro comandos na RDRAM sintética (SetOtherModes fill-cycle, SetColorImage RGBA16, SetFillColor, FillRectangle), executa a ponte e verifica pixels efetivamente escritos na mesma memória.
- Outros testes cobrem intervalo vazio, desalinhamento, limite da RDRAM e limite máximo de bytes.

Limitações deliberadas:
- **Ainda não é a implementação completa do DPC.** É uma ponte síncrona de leitura de uma lista na RDRAM para o subconjunto atual do RDP.
- Não há registradores DPC no barramento, START/END double-buffered, CURRENT, STATUS, DMA real, XBUS/DMEM, FIFO assíncrona, contadores, timing, freeze/flush ou interrupções.
- O processador atual pressupõe que cada comando suportado ocupa uma palavra de 64 bits. Não interpretar listas gerais com comandos multiword até implementar a decodificação de comprimentos do RDP.
- Não há integração com RSP nem com a CPU em execução contínua.
- Não há confirmação de renderização de jogo.

### Commits da etapa 46
- `46b63952990b77c7396da6be7929e78f9bd1afb1` — adiciona ponte síncrona de leitura de display list da RDRAM e dispatch para RDP.
- `62198544fcf54e8ff3e3cf4d8480c01135ad4c9f` — adiciona testes da ponte DPC→RDP.
- `a5c0a547b500d30553b0377c41c264ed2bdb88c0` — corrige o cenário de teste de limite: o primeiro teste usava `0xF8..0x100` numa RDRAM de 256 bytes, intervalo que termina exatamente no limite e é válido; o teste agora usa `0x100..0x108`, que realmente ultrapassa a RDRAM.

### Histórico de erros e correções que o próximo chat deve preservar
- Etapa 43: testes iniciais falharam por endereço/fixture de teste incorreto no cenário de cópia; corrigido no commit `81f64a0190a993fc9917995f6792afc5557e349e`, e CI #717 passou.
- Etapa 45: primeiro teste de limite do RDP não chegava a escrever fora da RDRAM; coordenadas foram ajustadas no commit `2ac17d48f1de690a82c63339dce5d732d4725472`.
- Revisão RDP: leitura de `cycle_type` estava nos bits errados; corrigida para bits globais 53:52 no commit `54cfe2dda9b24f8a2ea26a4189f0946c1b8af9b0`; fixtures ajustadas no `64f6d1683d5a235645e11cc8b34799ae31e3ba63`.
- Etapa 45: CI #732 (run `41252114386`) e #734 (run `41252139530`) tiveram falhas intermediárias. O teste #734 foi corrigido com fixture de limite real no commit `2ac17d48f1de690a82c63339dce5d732d4725472`; CI #735 (run `41252153682`) e #736 (run `41252187162`) concluíram com sucesso.
- Etapa 46: revisão identificou que o novo teste de overflow também estava incorreto: `0xF8..0x100` é válido para RDRAM de 256 bytes. Foi corrigido para `0x100..0x108` no commit `a5c0a547b500d30553b0377c41c264ed2bdb88c0`. A CI do HEAD ainda precisa ser conferida.
- Regra: não considerar uma falha intermediária como falha do HEAD final se houve correção posterior; mas também não declarar sucesso do HEAD atual usando CI de um commit anterior.

### Próximos passos — ordem recomendada
1. Consultar CI #739 (run `41254029960`) do HEAD `a5c0a547...`; examinar jobs e logs se falhar. Corrigir o HEAD atual e esperar nova CI.
2. Após CI verde, atualizar este documento com o resultado e o SHA documental.
3. Fazer revisão da semântica da ponte (big-endian, END exclusivo, alinhamento, overflow, comandos suportados) e manter testes.
4. Próxima integração real: adicionar modelo dos registradores DPC no `N64Bus` (START, END, CURRENT, STATUS e leituras/escritas), começando com modo RDRAM síncrono e explicitamente limitado ao subconjunto RDP atual. Testar escrita de START, avanço de CURRENT ao END, escrita em END disparando o processamento, leitura de status e erros de intervalo. Não implementar XBUS/DMEM, timing ou IRQ como se estivessem prontos.
5. Só depois adicionar suporte correto a fila/atualizações bufferizadas START/END, freeze/flush, status e comandos multiword; pesquisar as semânticas no guia SGI e em fontes independentes antes de codificar.
6. Integrar DPC/RDP com o mesmo `N64Bus`/RDRAM que o VI usa; criar teste end-to-end sintético que leia display list da RDRAM, produza framebuffer pela operação RDP suportada e capture-o pelo VI. Esse teste ainda será sintético e não significa que a ROM funcione.
7. Mais adiante, desenvolver RSP para gerar display lists a partir do microcódigo; somente então integrar BOOT HLE → CPU/RSP → DPC/RDP → RDRAM → VI num ciclo de emulação.
8. Manter `main` intocada. Não aumentar o limite de 2.000.000 instruções HLE para esconder loops. Não alegar renderização de GoldenEye ou outro jogo sem prova no aparelho.

### Estado da documentação
- Este documento deve ser atualizado a cada commit significativo, correção, CI e teste físico.
- O novo chat deve começar lendo esta seção 14, conferir HEAD/CI, e então executar o passo 1 acima. Não recriar o DPC command processor nem os testes já feitos.


## 17. Decodificação de comprimentos RDP antes da leitura DPC (2026-10-31)

### Resultado confirmado das etapas anteriores
- Build #744, run `41255289872`, commit `048b89d850c2b901f2505a573668113a99f82767`: **success**.
- Build #745, run `41255324500`, commit documental `1d53ff32a4d3164b3d8de29303419709f17ebe3f`: **success**.
- Portanto, o teste sintético DPC → RDP → RDRAM → VI e sua documentação passaram na CI.

### Problema encontrado na auditoria
O leitor DPC anterior assumia que cada comando RDP tinha exatamente 8 bytes. Isso é verdadeiro para os comandos de estado usados no subconjunto atual, mas não para todos os comandos do RDP. Triângulos têm payloads de tamanho variável e TextureRectangle/TextureRectangleFlip usam 16 bytes. Sem verificar comprimento, o leitor poderia interpretar palavras de payload de um comando multiword como se fossem novos opcodes.

### Implementação
Commit: `a5558895b53132a47e40c7190927d2ed4f0ec1ec`.

Arquivos:
- Criado `app/src/main/java/com/jsr/n64android/N64RdpCommandLength.kt`.
- Atualizado `app/src/main/java/com/jsr/n64android/N64DpcCommandProcessor.kt`.
- Criado `app/src/test/java/com/jsr/n64android/N64RdpCommandLengthTest.kt`.
- Atualizado `app/src/test/java/com/jsr/n64android/N64DpcCommandProcessorTest.kt`.

Comprimentos reconhecidos:
- Triângulos 0x08–0x0F: 32, 48, 96, 112, 96, 112, 160 e 176 bytes, respectivamente.
- TextureRectangle 0x24 e TextureRectangleFlip 0x25: 16 bytes.
- Demais opcodes: 8 bytes nesta tabela de comprimentos.

Comportamento:
- O DPC lê o opcode de 6 bits do primeiro word e consulta a tabela antes de avançar.
- Comandos de 8 bytes continuam sendo enviados ao RDP atual.
- Comandos multiword reconhecidos são rejeitados com `UnsupportedOperationException` explícita até que seus payloads e execução sejam implementados.
- Comando multiword truncado pelo END é rejeitado com mensagem de comprimento insuficiente.
- Isso impede que os words de payload sejam interpretados como comandos independentes.
- A implementação **não** decodifica ou rasteriza triângulos, retângulos texturizados ou qualquer comando multiword; apenas identifica o comprimento e recusa a execução com segurança.

Testes:
- Tabela dos oito comprimentos de triângulos.
- Comprimento de 16 bytes dos dois comandos TextureRectangle.
- Comandos de estado suportados com comprimento de 8 bytes.
- Rejeição de opcode fora de 0..63.
- DPC rejeita triângulo multiword em vez de interpretar payload como novos comandos.
- DPC rejeita triângulo truncado pelo END.

### Referências técnicas
- Project64, tabela `rdp_command_length[64]` e despacho RDP, com os comprimentos dos opcodes 0x08–0x0F e 0x24–0x25: https://github.com/project64/project64/blob/develop/Source/Project64-video/rdp.cpp
- Uma tabela independente com os mesmos comprimentos está reproduzida em documentação de código N64: https://cocalc.com/github/RishiRecon/exploits/blob/main/misc/emulator/n64/emulator/code/src/gles2n64/src/RDP.c
- Essas tabelas foram usadas apenas para reconhecer tamanhos; a execução dos comandos continua deliberadamente não implementada.

### CI
- O commit de código `a5558895b53132a47e40c7190927d2ed4f0ec1ec` disparou um novo workflow após esta atualização; consultar o número/run atual e aguardar **Build and test** antes de considerar esta etapa validada.
- Nenhum teste físico em aparelho foi feito nesta etapa.
- Branch permanece `dev/n64-clean-start`; `main` intocada.

### Próximo passo
1. Verificar CI do commit de código e corrigir falhas se houver.
2. Após aprovação, registrar o resultado da CI e atualizar o SHA documental.
3. Antes de implementar comandos multiword, adicionar testes de parsing de stream para todos os comprimentos, limites e ENDs truncados.
4. Depois implementar o primeiro comando multiword apenas com uma representação de payload explícita e testes; não começar a rasterização de triângulos sem cobrir corretamente interpolantes, clipping, texturas e limites.
5. Manter a integração DPC/RDP síncrona como modelo experimental, não FIFO completa. Não afirmar que uma ROM é renderizada.


## 18. Validação integral do stream DPC e cobertura de truncamento (2026-10-31)

### CI confirmada
- Build #746, run `41255553298`, commit de código `a5558895b53132a47e40c7190927d2ed4f0ec1ec`: **success**.
- Build #747, run `41255572693`, commit documental `409c8c4fe483ce1ebce3041b2d375060e06f228a`: **success**.
- Build #748, run `41255714778`, commit `4050cb0ec3309a8dc30b4a8cb4d4cb206f32b13d`: **success**, incluindo Build and test e Upload debug APK.

### Testes adicionados no commit `4050cb0ec3309a8dc30b4a8cb4d4cb206f32b13d`
- Lista com prefixo de comandos RDP de 8 bytes seguido de triângulo multiword: confirma que o DPC rejeita antes de chamar `rdp.execute`, portanto o prefixo não modifica estado nem framebuffer.
- TextureRectangle (opcode `0x24`) com END antes dos 16 bytes exigidos: confirma rejeição de comando truncado.
- A validação do stream ocorre antes do despacho da lista completa. A implementação ainda não cria pacotes multiword nem executa seus payloads.

### Cobertura adicional no commit `14e78041e4eaf419f7fade63b82696b6027a44dd`
- Teste parametrizado para todos os opcodes multiword conhecidos: `0x08`–`0x0F`, `0x24` e `0x25`.
- Para cada opcode, o END é colocado 8 bytes antes do comprimento total; o teste exige `IllegalArgumentException` com identificação de comando truncado.
- Build #749, run `41255996859`, disparado para este commit. Na atualização desta seção estava **queued/in progress**; conferir o resultado no GitHub antes de declarar a cobertura aprovada.

### Limites preservados
- O leitor continua reconhecendo comprimento, validando END e rejeitando comandos multiword. Ainda não há representação `RdpCommandPacket` nem execução de triângulos/retângulos texturizados.
- A tabela de comprimentos não substitui a implementação de payload, interpolantes, clipping, textura/TMEM, Z-buffer ou rasterização.
- O processador DPC continua síncrono e experimental; não é uma FIFO DPC completa.
- Nenhuma ROM foi confirmada como renderizada no aparelho nesta etapa.

### Próximo passo técnico
1. Confirmar CI #749 e registrar seu resultado.
2. Fazer uma pequena abstração pura de parsing: pacote com opcode, comprimento e lista imutável de words (incluindo payload), sem executar comandos multiword.
3. Testar parsing de listas mistas, todos os comprimentos, END exclusivo, truncamento e limite máximo antes de ligar qualquer pacote multiword ao RDP.
4. Só então escolher um comando multiword para implementação; não iniciar triângulos sem planejar corretamente o pipeline gráfico.
5. Manter `dev/n64-clean-start` como branch de trabalho e `main` intocada.


## 19. Pacotes completos do stream RDP (2026-11-01)

### CI da etapa anterior confirmada
- Build #749, run `41255996859`, commit `14e78041e4eaf419f7fade63b82696b6027a44dd`: **success** (Build and test + Upload debug APK).
- Build #750, run `41256025839`, commit documental `3d390770a99decb96a9ebf029cf7ed1e171ed027`: **success**.
- HEAD da branch antes desta etapa: `3d390770a99decb96a9ebf029cf7ed1e171ed027`.

### Implementação
Arquivos:
- Criado `app/src/main/java/com/jsr/n64android/N64RdpCommandStreamParser.kt`.
- Atualizado `app/src/main/java/com/jsr/n64android/N64DpcCommandProcessor.kt`.
- Criado `app/src/test/java/com/jsr/n64android/N64RdpCommandStreamParserTest.kt`.

Modelo:
- `N64RdpCommandPacket` contém opcode de 6 bits, comprimento em bytes e lista de words de 64 bits.
- `N64RdpCommandStreamParser` lê o intervalo RDRAM [START, END exclusivo], com words big-endian.
- Para cada opcode, consulta `N64RdpCommandLength` e recolhe todas as palavras do pacote, incluindo payload.
- O parser valida alinhamento, ordem do intervalo, limite declarado da RDRAM, limite configurável de 1 MiB e comprimento integral de cada pacote.
- O retorno é uma lista separada da memória original; o parser não executa comandos nem altera RDRAM.

Integração:
- O `N64DpcCommandProcessor` agora chama o parser para obter a lista inteira antes de despachar qualquer comando.
- Comandos de 8 bytes seguem para o `N64RdpFillProcessor` como antes.
- Comandos multiword são preservados como pacotes, mas continuam rejeitados com `UnsupportedOperationException` antes de qualquer dispatch. Não há execução multiword ainda.

### Testes novos
- Preservação de todos os quatro words de um triângulo de 32 bytes e leitura correta do comando seguinte.
- Preservação dos dois words do TextureRectangle de 16 bytes.
- Snapshot dos words não muda quando a memória de origem é alterada após o parsing.
- Rejeição de comando multiword truncado pelo END.
- Rejeição de START desalinhado e intervalo fora da RDRAM.
- Aceitação de intervalo vazio.
- Os testes antigos do DPC continuam cobrindo que uma lista inteira é validada antes do dispatch, sem executar prefixo válido se houver multiword não suportado depois.

### Commits de código
- `a1ac897672e4e003ad5f5a4e30b4083552e0cf9d` — adiciona parser e modelo de pacote RDP.
- `7be5560c121cfcca392de4090372b75fa6b99eea` — integra parser ao DPC.
- `399bd9fc086309c10dcddbe03239d5736afc1094` — adiciona testes de preservação de payload e validação.

### CI desta etapa
- Build #751, run `41290765794`, parser: em execução na hora desta atualização.
- Build #752, run `41290768232`, integração DPC: em execução na hora desta atualização.
- Build #753, run `41290780039`, testes: em execução na hora desta atualização.
- Confirmar todos os runs e o HEAD atual antes de considerar a etapa aprovada; se houver falha, buscar logs, corrigir no HEAD atual e aguardar nova CI.
- A criação de três commits em sequência disparou workflows separados. O HEAD da branch deve ser conferido; o commit de teste foi o último observado.

### Limites e próximo passo
- Reconhecer e preservar payload não é implementar a execução de triângulos nem retângulos texturizados.
- Não há decodificação de interpolantes, clipping, textura/TMEM, Z-buffer, rasterização de triângulos ou pipeline RDP completo.
- O DPC continua síncrono e experimental, sem FIFO real, timing, DMA XBUS/DMEM ou IRQ.
- Nenhuma ROM foi confirmada como renderizada no aparelho nesta etapa.
- Próxima etapa, depois de CI verde: revisar se a API do pacote deve ser endurecida para imutabilidade efetiva e adicionar testes de todos os comprimentos multiword em listas mistas. Depois escolher um comando multiword de baixa complexidade para implementar com especificação e fixtures independentes; não começar rasterização de triângulos sem o pipeline gráfico planejado.
- Branch obrigatória: `dev/n64-clean-start`. Manter `main` intocada.


## 20. Correção final dos testes do parser RDP e CI aprovada (2026-11-01)

### Resultado final confirmado
- HEAD de código validado: `e6dba5d3c60ed4d79105f158ef0c90c7f8804ea5`.
- Build #757, run `41291055717`: **success**.
- Job `build`: concluído com sucesso.
- Etapas `Build and test` e `Upload debug APK`: ambas concluídas com sucesso.
- Artefato confirmado: `n64-android-debug-apk`, ID `13077387211`, tamanho 925.493 bytes, criado em 2026-11-01 e com expiração em 2026-11-15.
- Link da compilação: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41291055717
- Link do artefato: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41291055717/artifacts/13077387211
- Link do commit: https://github.com/jovemaprendz55-byte/N64-Android/commit/e6dba5d3c60ed4d79105f158ef0c90c7f8804ea5

### Correções que levaram ao resultado verde
- #753 (run `41290780039`) falhou na compilação dos testes Kotlin por literais hexadecimais unsigned fora do intervalo de `Long`.
- #754 (run `41290835725`) também falhou porque os testes ainda não compilavam.
- #755 (run `41290859691`) falhou após uma primeira tentativa de corrigir os valores dos fixtures.
- #756 (run `41290954083`) compilou o APK e executou 347 de 348 testes; falhou em `rejectsRangeOutsideRdram`, porque o END usado era exatamente o limite exclusivo válido da RDRAM.
- Commit `e6dba5d3c60ed4d79105f158ef0c90c7f8804ea5` corrigiu o fixture para ultrapassar realmente o limite da RDRAM e renomeou o teste para `returnsDetachedSnapshotOfSourceWords`, descrevendo corretamente o comportamento: snapshot separado da memória de origem, sem prometer imutabilidade efetiva da lista.
- A CI #757 confirmou que a correção final passou em Build and test e Upload debug APK.

### Estado técnico que permanece
- O parser agora recolhe pacotes RDP completos e preserva words de payload.
- A integração DPC valida a lista toda antes de despachar comandos.
- Comandos RDP de 8 bytes suportados continuam seguindo para o processador fill-cycle.
- Comandos multiword são identificados e preservados, mas ainda rejeitados antes da execução. Triângulos e retângulos texturizados não são rasterizados.
- O snapshot é destacado da memória original, mas a lista não deve ser descrita como imutável.
- DPC continua síncrono e experimental; não há FIFO real, START/END bufferizados, DMA XBUS/DMEM, timing ou IRQ.
- O teste sintético DPC → RDP → RDRAM → VI continua sendo evidência de integração interna sintética, não de execução de ROM. Nenhum jogo foi confirmado renderizado no aparelho nesta etapa.

### Próxima sessão — ordem de trabalho
1. Confirmar que o HEAD da branch `dev/n64-clean-start` continua em `e6dba5d3c60ed4d79105f158ef0c90c7f8804ea5`; não alterar `main`.
2. Se o usuário ainda não instalou o APK, pode instalar o artefato #757 e relatar o comportamento da aplicação; a tela de teste sintético não comprova renderização de ROM.
3. Revisar a API do pacote e decidir se vale proteger a lista de words com uma coleção efetivamente não modificável, incluindo testes adequados.
4. Adicionar testes de listas mistas cobrindo os comprimentos multiword e o comando válido seguinte, garantindo que o parser mantenha os limites dos pacotes.
5. Só depois selecionar um comando multiword de baixa complexidade para implementação, com especificação técnica e fixtures independentes. Não iniciar triângulos sem planejar interpolantes, clipping, texturas/TMEM, Z-buffer e rasterização.
6. Atualizar esta documentação após cada etapa significativa, incluindo commit, CI, falhas intermediárias, correções e observações no aparelho.

### Regra de encerramento diário
- Trabalhar em sessões de aproximadamente 1–2 horas, sem sacrificar a validação.
- Encerrar o dia somente em ponto seguro: alteração registrada, testes/CI verificados quando houver commit e documentação atualizada com o próximo passo.
- Avisar explicitamente ao usuário na conversa: “Por hoje está bom. Amanhã continuamos daqui.”
- Não prometer mensagem espontânea fora de uma conversa ativa; o aviso é feito enquanto o usuário estiver conversando.


## 21. Pacotes RDP com snapshot efetivamente não modificável (2026-11-01)

### Implementação
- Commit `f822503373dbb2545a7573dfb3591eba1642e0c6`: `N64RdpCommandPacket` deixou de ser data class com referência direta à lista recebida.
- O construtor agora cria uma cópia defensiva dos words e a expõe por `Collections.unmodifiableList`.
- O parser também retorna a coleção de pacotes envolvida por `Collections.unmodifiableList`, impedindo mutações por cast para `MutableList`.
- A memória RDRAM de origem continua independente dos words armazenados no pacote.
- Commit `9fe75a0b3f9116710c595f9b7f0e8da898c7ae3c`: adiciona teste de que alterar a lista fonte não altera o pacote, mutar `packet.words` lança `UnsupportedOperationException`, e limpar a coleção de pacotes também lança `UnsupportedOperationException`.

### CI e APK
- Build #758, run `41291578677`, documentação da seção 20: **success**.
- Build #759, run `41291665346`, commit `f822503373dbb2545a7573dfb3591eba1642e0c6`: **success** (Build and test + Upload debug APK).
- Build #760, run `41291676979`, commit `9fe75a0b3f9116710c595f9b7f0e8da898c7ae3c`: **success** (Build and test + Upload debug APK).
- Artefato #760: `n64-android-debug-apk`, ID `13076943692`, 925.297 bytes, expira em 2026-11-15.
- Compilação #760: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41291676979
- APK #760: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41291676979/artifacts/13076943692
- Commit da alteração de código: https://github.com/jovemaprendz55-byte/N64-Android/commit/9fe75a0b3f9116710c595f9b7f0e8da898c7ae3c

### Estado e limites
- A lista de words de cada pacote e a lista de pacotes retornada pelo parser são agora cópias protegidas contra mutação.
- Isso não implementa comandos multiword: triângulos e retângulos texturizados continuam sem execução/rasterização.
- A ponte DPC segue síncrona e experimental; não é uma FIFO DPC completa.
- Não houve confirmação de ROM renderizada em aparelho nesta etapa.

### Próximo passo
1. Confirmar CI da atualização documental.
2. Adicionar testes de parsing para todos os opcodes multiword em listas mistas, conferindo que o parser salta exatamente o payload e encontra o comando seguinte.
3. Escolher um comando multiword simples e documentado para implementação, com fixtures independentes; evitar iniciar triângulos sem planejamento do pipeline completo.
4. Manter a branch `dev/n64-clean-start` e nunca alterar `main`.
5. Encerrar a sessão somente depois da CI documental verde e do checkpoint registrado; ao parar, avisar explicitamente o usuário que o trabalho do dia terminou.


## 22. Início do pipeline de geometria RDP: decoder de triângulos (2026-11-01)

### Objetivo desta etapa
Começar a substituir o caminho de teste sintético por processamento gráfico real, sem afirmar que já existe renderização de ROM. A imagem colorida continua sendo gerada exclusivamente por `N64VideoTestPattern.createFrame()` quando o usuário aciona o botão de teste de vídeo.

### Implementação
- Criado `N64RdpTriangleSetup.kt`.
- O decoder reconhece os opcodes de triângulo nativos `0x08..0x0F` e decodifica o prefixo de quatro words de coeficientes de aresta.
- Campos decodificados: opcode, flag right-major, tile, YL/YM/YH (signed 12.2), XL/DxLDy, XH/DxHDy e XM/DxMDy (signed 16.16).
- O decoder aceita o pacote completo conforme o comprimento indicado por `N64RdpCommandLength`, mas interpreta somente o grupo inicial de edge coefficients. Coeficientes shade/texture/Z ainda não são decodificados.
- Testes em `N64RdpTriangleSetupTest.kt`: campos/fixed-point e sinal, flag de orientação, todos os oito opcodes e rejeição de comando não-triângulo.
- Isso é somente decodificação. Não rasteriza triângulos, não escreve pixels e ainda não é conectado ao despacho DPC/RDP.

### Referências técnicas usadas para o layout
- SGI/Nintendo Ultra64 RDP Command Summary, versão 2.0, tabelas 11 e 12 (triangle command groups e edge coefficients): https://ultra64.ca/files/documentation/silicon-graphics/SGI_RDP_Command_Summary.pdf
- Project64 RDP implementation (referência independente para layout/consumo dos coeficientes): https://github.com/project64/project64/blob/develop/Source/Project64-video/rdp.cpp
- Não copiar nem integrar núcleo de emulador externo; usar as referências somente para entender o formato de hardware.

### Commits e CI
- Commit `cd07ab0797eac13b03589d877f4b1cdfecb2addc`: decoder de coeficientes de aresta.
- Build #762, run `41292274237`: **success**; Build and test + Upload debug APK.
- Artefato #762 `n64-android-debug-apk`, ID `13077772829`, 927.664 bytes.
- Commit `e467b9b69bab2c05827044f808d0e101cf610781`: testes do decoder.
- Build #763, run `41292283769`: **success**; Build and test + Upload debug APK.
- Artefato #763 `n64-android-debug-apk`, ID `13077329604`, 927.661 bytes.
- Compilação #763: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292283769
- APK #763: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292283769/artifacts/13077329604

### Limitações reais encontradas no caminho de vídeo
- `N64VideoTestPattern.createFrame()` gera explicitamente as barras de cor sintéticas.
- `N64RdpFillProcessor` executa apenas SetOtherModes, SetColorImage, SetScissor, SetFillColor e FillRectangle; não rasteriza triângulos nem texturas.
- `N64DpcCommandProcessor` ainda rejeita todos os pacotes multiword antes de executar comandos; o novo decoder não foi integrado ao DPC.
- Não existe ainda implementação de RSP/microcode que execute as tarefas gráficas F3D/F3DEX e produza display lists de ROM para o RDP.
- A captura VI atual é uma captura única após o diagnóstico HLE; não existe loop de frames/VBlank nem apresentação contínua de vídeo.
- Portanto, nenhum quadro de jogo foi produzido nesta etapa. O APK #763 não deve ser anunciado como renderizador de jogos.

### Próximos passos do pipeline real
1. Integrar os pacotes de triângulo ao dispatcher RDP, mantendo validação completa do stream antes de qualquer escrita.
2. Implementar rasterização flat/unshaded de triângulos com edge-walking, clipping e testes de pixels contra fixtures independentes. Registrar explicitamente o subset não suportado.
3. Implementar o estado de cor/combiner necessário para produzir pixels corretos; não assumir que o flat color por si só cobre jogos.
4. Implementar o núcleo RSP e a execução de microcode de display-list (instruções escalares e vetoriais necessárias), além do caminho SP task → DPC.
5. Ligar a captura VI a quadros efetivamente produzidos pelo RDP e criar apresentação contínua no Android; remover o padrão de cores do caminho de jogo.
6. Validar primeiro com display lists sintéticas, depois com ROM no aparelho. Só declarar renderização real quando houver imagem capturada no dispositivo originada de uma ROM.

### Branch e encerramento
- Branch de trabalho: `dev/n64-clean-start`; manter `main` intocada.
- A CI #763 está verde. Atualizar esta seção documental após cada nova etapa e incluir commit, workflow, status e APK somente quando o artefato existir.


## 23. Edge-walking experimental para triângulos RDP (2026-11-01)

### Implementação
- Criado `N64RdpTriangleEdgeWalker.kt`.
- Recebe `N64RdpTriangleSetup`, dimensões de framebuffer e retângulo de clipping; produz spans horizontais [xStart, xEndExclusive) por linha.
- Usa amostragem no centro do pixel, coordenadas Y 12.2 e X/slope 16.16; escolhe a aresta menor superior/inferior conforme YM e inverte esquerda/direita pelo bit right-major.
- Inclui clipping ao framebuffer e ao retângulo fornecido.
- Testes em `N64RdpTriangleEdgeWalkerTest.kt`: spans superior/inferior, clipping, orientação right-major, inclinação e faixa vertical degenerada.

### Limite importante
- O edge-walker é experimental e ainda não foi validado contra hardware ou uma implementação de referência pixel a pixel. As regras de cobertura/subpixel e empates precisam de validação adicional.
- Ele apenas produz spans; não escreve pixels na RDRAM e ainda não está ligado ao dispatcher RDP.
- Não representa vídeo real de ROM. Não remover o padrão de cores da UI até existir pipeline de pixels de ROM validado.

### Commits e workflows
- Commit `d23925d2cd55f52449d59fff3e5bdd6a062762ee`: edge-walker.
- Build #765, run `41292429433`: verificar status final.
- Commit `fcdb3da829c61219e8a60caf3688d1ce9ff83d24`: testes.
- Build #766, run `41292439111`: verificar status final e artefato.
- Compilação #765: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292429433
- Compilação #766: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292439111

### Próximo passo
1. Confirmar #765/#766 verdes e obter o APK de #766 se o upload passar.
2. Criar testes de referência para triângulos com orientação esquerda/direita, Y fracionário, inclinação positiva/negativa, clipping e limites de framebuffer.
3. Comparar os spans com uma implementação de referência ou fixtures derivadas do documento SGI RDP, documentando diferenças de cobertura.
4. Depois implementar escrita de spans em RDRAM com estado de cor correto e só então integrar o pacote multiword no DPC.
5. Em paralelo, planejar/implementar o RSP e microcode F3DEX; sem o RSP, jogos não geram display lists reais para o RDP.


## 24. Primeiro rasterizador flat e caminho RDP sintético até a tela Android (2026-11-01)

### Estratégia de desenvolvimento
A partir desta etapa, priorizar fatias verticais pequenas e demonstráveis: gerar pixels, armazená-los em RDRAM, capturá-los pelo VI e apresentá-los no Android. Não acumular decoders isolados sem integração. Todo código é implementação própria; documentação de hardware pode orientar formatos, sem copiar código de outros emuladores.

### Implementação desta etapa
- Criado `N64RdpFlatTriangleRasterizer.kt`.
- Consome o setup de triângulo e os spans do edge-walker experimental, grava pixels flat RGBA5551 em RDRAM e valida os limites do framebuffer antes da escrita.
- Criado `N64TrianglePipelineDemo`: prepara framebuffer sintético, rasteriza um triângulo vermelho, configura VI e captura um `N64VideoFrame`.
- Adicionado botão Android **Renderizar triângulo (RDP → RDRAM → VI)**, que mostra o frame retornado pelo VI e informa spans/pixels escritos.
- Criados testes para escrita de pixels, proteção de limites e captura do triângulo no frame final.

### Limites técnicos
- É uma demonstração sintética, não uma ROM, jogo ou display list produzida pelo RSP.
- O rasterizador ainda cobre somente triângulos flat RGBA5551 usando o edge-walker experimental. Não implementa coverage/antialiasing real, combiner, shade, textura, Z, blending, dither ou regras de subpixel completas.
- O caminho de demonstração chama o rasterizador diretamente; ainda não está integrado ao parser/dispatcher DPC/RDP nem ao microcode RSP.
- A imagem no aparelho, quando testada, comprova apenas o caminho sintético RDP-like → RDRAM → VI → Android. A primeira renderização de jogo só será declarada depois de uma ROM real gerar o framebuffer.

### Commits e CI
- Rasterizador: `8cc32503afae79bff4f8e146efd358b8708bce92`.
- Testes: `fdf538752f98a567dddaecee2ac6c0c1050abfa1`.
- Integração da tela Android: `67357a81b846aeed49ed48cac4aee2438be70ba2`.
- Workflow #768 run `41292933449`, #769 run `41292937667` e #770 run `41292978215`: status a confirmar; não considerar verdes até conclusão.
- Links: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292933449
  https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292937667
  https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41292978215

### Próximas etapas rápidas
1. Confirmar CI e corrigir qualquer falha antes de instalar o APK.
2. Validar o triângulo no aparelho Android e comparar a imagem com os testes de framebuffer.
3. Melhorar cobertura/edge rules com fixtures independentes e implementar mais formatos de cor/estado necessário.
4. Integrar comandos multiword de triângulo ao parser/dispatcher DPC/RDP com validação integral antes de escrita.
5. Implementar execução mínima de display-list via RSP/microcode para que o pipeline passe a receber comandos originados de uma ROM.
6. Só depois de uma ROM produzir pixels, remover a distinção de demonstração sintética da UI.


## 25. Execução de triângulo flat através de display list DPC (2026-11-01)

### Integração concluída
- O parser continua lendo e validando pacotes completos de RDP em big-endian antes da execução.
- O DPC agora encaminha os pacotes completos ao processador RDP, em vez de rejeitar todos os comandos multiword.
- Primeiro comando multiword executado: opcode `0x08`, triângulo flat sem shade, textura ou Z.
- O RDP mantém o estado parcial de SetColorImage, SetScissor e SetPrimColor e grava RGBA5551 na RDRAM usando o rasterizador flat.
- Foi incluído preflight de suporte para todos os pacotes antes de alterar estado ou escrever pixels; comandos multiword não suportados continuam sendo rejeitados.
- A demonstração Android agora constrói uma display list sintética em RDRAM, chama o DPC, recebe os pixels do RDP, configura o VI e exibe o frame. O botão existente agora exercita DPC → RDP → RDRAM → VI → Android, não chama diretamente o rasterizador.

### Testes e CI
- Teste de integração confirma 3 comandos de estado + 1 triângulo multiword processados pelo DPC, pixels gravados em RDRAM e frame capturado pelo VI.
- CI #775, run `41293641540`: success; valida o preflight e a rejeição atômica de comandos não suportados.
- CI #779, run `41293804731`: success; Build and test + Upload debug APK.
- APK #779 artifact ID `13078361370`, disponível até 2026-11-15.
- CI #780, run `41293816234`: validar status final (refatoração de coordenadas constantes).
- Links:
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41293641540
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41293804731
  - https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/41293816234

### Limites e próximo trabalho
- A display list ainda é sintética e construída pelo código de demonstração; não vem de uma ROM nem do RSP.
- O opcode 0x08 é somente o subconjunto flat RGBA16. Shade/texture/Z triangles, cobertura fiel ao hardware, combiner, blending, dither, precisão subpixel e timing ainda faltam.
- O edge walker permanece experimental e necessita comparação de cobertura com vetores de referência independentes.
- Próxima prioridade: testar o novo APK no aparelho; depois ligar a execução de display lists originadas pelo SP/RSP, começando por um microprograma controlado e expandindo até um microcode gráfico comum. Não declarar jogo funcional até uma ROM real produzir o framebuffer.
- Desenvolvimento integralmente próprio; documentação técnica pode ser usada para compreender os formatos e registradores, sem copiar código-fonte de outros emuladores.

## Atualização da interface inicial — 2026-11-01

- O usuário instalou a build anterior e confirmou visualmente no aparelho a demonstração sintética do triângulo RDP. Isso valida somente o caminho de teste DPC → RDP → RDRAM → VI → ImageView; não é evidência de uma ROM/jogo funcionando.
- A tela inicial foi reorganizada no commit `15532fe16646573aefeb9e42f7ac57e899f3fed6` na branch `dev/n64-clean-start`.
- A tela principal agora prioriza um único botão “ADICIONAR JOGO”, mostra o estado da ROM e recolhe os testes internos em “FERRAMENTAS DE DESENVOLVIMENTO”.
- CPU, padrão de cores, triângulo RDP, diagnóstico IPL3 e diagnóstico HLE continuam acessíveis dentro do painel; os dois diagnósticos dependentes de ROM permanecem desativados até carregar uma ROM.
- Próximo passo: aguardar/verificar CI e APK desta alteração; instalar no Galaxy A15 e confirmar se o menu recolhido abre/fecha e se os testes continuam acessíveis. Depois, aprimorar biblioteca/lista de ROMs sem alegar suporte de execução antes de testar um jogo real.
- Correção complementar no commit `3dd8fedb9e9a1f65bef90e3076196ad241518cf7`: a mensagem de estado vazio usa quebra de linha real, sem exibir os caracteres `\\n` literalmente.
- Continuação da interface em 2026-11-01, commit `f43ef6518b3f377680bd38eaecfc77c66f207839`: ao carregar uma ROM válida, a tela inicial passa a exibir o nome real do arquivo (via `OpenableColumns.DISPLAY_NAME`), tamanho e formato, em vez de colocar o relatório técnico extenso do inspetor no menu. O relatório detalhado deixa de ser mostrado nesse status principal; falhas de leitura/carregamento continuam exibidas.


## 26. Primeiro núcleo escalar próprio do RSP — 2026-11-01

### Implementação
- Criado `N64RspCore.kt`, um interpretador escalar independente para o processador RSP. Não reutiliza o núcleo R4300 e não incorpora código de outros emuladores.
- Busca instruções na IMEM (4 KiB), mantém 32 registradores GPR de 32 bits e PC SP de 12 bits.
- Primeiro subconjunto implementado: shifts, operações aritméticas/lógicas, comparações, saltos e branches com delay slot, imediatos, loads/stores byte/meia-palavra/palavra em DMEM, e BREAK/HALT.
- Execução limitada por orçamento de instruções; o RSP pode ser avançado passo a passo ou por lote.
- Criado `N64RspCoreTest.kt` para testar aritmética, leitura/escrita DMEM, delay slot, desvio do fall-through, BREAK e limite de execução.

### Limites desta fatia
- Ainda não há instruções vetoriais COP2, registradores vetoriais/flags, accumulator, operações de clipping, COP0 do SP, task scheduler, interrupções/timing nem microcode gráfico F3D/F3DEX.
- O núcleo ainda não está conectado ao ciclo de vida de tarefas SP iniciado por uma ROM. Este marco prova somente a execução unitária do subset escalar em IMEM/DMEM.
- O caminho até jogo real continua dependendo de completar o conjunto escalar necessário, COP0/SP task, DMA coerente, vetor RSP e microcode gráfico que produza comandos RDP.
- Não declarar ROM ou jogo funcional com base nestes testes unitários.

### Commits
- `8cbba454f304eb5d7c9b952b63077f781b0751a4`: primeiro núcleo escalar RSP.
- `a7d0b18862efde24aeb27903f1cf308f93fa0872`: correções de estado HALT e contagem limitada.
- `eaa665200190551339b02d5d57b496b18245927e`: testes iniciais do RSP.
- `ab384a8731f2b51ce196f234a9099a980cecd4ae`: correção do destino de JALR.

### Próximos passos imediatos
1. Rodar CI e corrigir qualquer falha.
2. Expandir o conjunto escalar com testes de cada instrução e exceções/alinhamento.
3. Implementar COP0 mínimo do SP e acoplamento de execução ao SP_STATUS/SP_PC.
4. Implementar execução de task SP controlada a partir de IMEM/DMEM, com término/BREAK e geração de display list em RDRAM.
5. Só então iniciar subset vetorial e microcode gráfico; manter a demonstração sintética separada do caminho de ROM.


## 27. COP0 mínimo do SP e semântica de BREAK — 2026-11-01

- O RSP agora decodifica MFC0 e MTC0 para o subconjunto de registradores SP: MEM_ADDR, DRAM_ADDR, RD_LEN, WR_LEN, STATUS, DMA_FULL, DMA_BUSY e SEMAPHORE.
- Escritas em DMA_FULL/DMA_BUSY são ignoradas por serem registradores somente de leitura; os demais passam pelo mapa MMIO já existente no barramento.
- A instrução BREAK agora marca simultaneamente SP_STATUS.HALT e SP_STATUS.BROKE, em vez de apenas parar o núcleo.
- Testes ampliados para leitura/escrita de SP_STATUS via COP0 e confirmação dos bits HALT/BROKE após BREAK.
- Limite importante: isso ainda não equivale a um executor completo de tarefas SP; falta modelar o ciclo de vida de task, interrupções, sincronização e microcode.

Commits:
- `0bbed8817d9aaf6d67de795b68be612384e7b8af`: sinalização arquitetural de BREAK no barramento.
- `b35159de5fc343242655ac282535833c7a909309`: MFC0/MTC0 do SP.
- `d8c43a5f81ca9f5341d640a91d49ad70c8fb2dd3`: testes COP0 e BREAK.

Próximo passo: validar CI; depois implementar registradores vetoriais/accumulator e operações COP2 de forma incremental, com testes de largura, saturação e flags, antes de abordar microcode gráfico.

## 28. Estado vetorial inicial do RSP — 2026-11-01

### Implementação
- Criado `N64RspVectorUnit.kt`, separado do núcleo escalar, com 32 registradores vetoriais × 8 lanes de 16 bits e accumulator de 48 bits por lane.
- Primeiro subconjunto de operações lane-a-lane: ADD e SUB com interpretação signed, saturação para signed 16-bit e consumo do bit VCO-low como carry/borrow-in; AND, OR, XOR e NOR.
- O ADD/SUB atualiza o accumulator com o resultado intermediário sign-extended para 48 bits e limpa VCO-low/high.
- Estado de flags VCO, VCC e VCE é armazenado; VCC/VCE podem ser definidos para preparar os testes/implementações seguintes.
- Testes em `N64RspVectorUnitTest.kt`: largura/índices dos registradores, saturação, carry/borrow-in, operações lógicas e accumulator.

### Limites importantes
- Esta etapa cria e testa o estado vetorial e um subconjunto de operações, mas **ainda não integra o decoder COP2 ao N64RspCore**.
- Não há ainda instruções de load/store vetorial, elemento/element select, operações de comparação/merge, multiplicação/acumulação vetorial nem semântica completa das flags para cada opcode.
- Não existe ainda execução de microcode F3D/F3DEX nem ligação com tarefas SP disparadas por ROM.
- Portanto, não declarar que o RSP executa microcode gráfico ou que algum jogo renderiza.

### Commits
- `a774074a6ea15595918ee87d2e59b15e6333eac5`: estado vetorial e operações iniciais.
- `ae5a6d478d0d99d596d47bb7e3d38443dc6b937f`: testes da unidade vetorial.

### Próximo passo
1. Confirmar CI e corrigir qualquer falha.
2. Validar a semântica das operações vetoriais e flags com vetores de teste independentes.
3. Integrar decodificação COP2 no N64RspCore, incluindo extração correta dos campos de instrução.
4. Implementar loads/stores vetoriais e operações necessárias ao microcode gráfico, com testes por opcode.
5. Conectar execução SP task → RSP → display list → DPC/RDP; manter demonstração sintética separada do caminho de ROM.
