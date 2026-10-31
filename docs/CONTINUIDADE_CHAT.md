# CONTINUIDADE DO PROJETO — N64 Android

> Documento de passagem de contexto para qualquer novo chat ou colaborador. Leia este arquivo antes de alterar código. Atualize-o sempre que houver mudança relevante, teste, build, log ou decisão técnica.

**Última atualização:** 2026-10-31  
**Repositório:** https://github.com/jovemaprendz55-byte/N64-Android  
**Branch de trabalho obrigatória:** `dev/n64-clean-start`  
**Último commit de código registrado:** `227040724945b051343dce2ab9d6aed41b9e3088`  
**Último commit de documentação:** `6cb9063645d127ba5a6148b08cc97085021dd63e`  
**Build mais recente verificada:** GitHub Actions #597 — sucesso para documentação; build #598 do novo diagnóstico está em andamento.

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

A branch `dev/n64-clean-start` estava no commit:

```
0ea295fab125cc60fc979e9052c62969a76be59c
Make HLE entry fetch test use deterministic NOP payload
```

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

A rotina `runHleBootDiagnostic()` foi preparada para:
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

## 8. Próximos passos — ordem recomendada

### Passo 1 — conferir o estado atual antes de qualquer edição
- Consultar HEAD da branch `dev/n64-clean-start`.
- Conferir se há commits novos depois de `0ea295f`.
- Verificar estado do run #592 e artifact se ainda for necessário.
- Ler `N64HleBoot.kt`, testes, `MainActivity.kt`, CPU e barramento na versão atual.
- Não confiar cegamente neste documento se o código já tiver avançado; o GitHub/HEAD atual é a fonte de verdade.

### Passo 2 — obter o primeiro log do novo HLE no aparelho
- Confirmar se o usuário instalou a APK da build #592 ou build mais recente.
- Pedir log completo da página “Iniciar jogo (HLE sem IPL3)”.
- Analisar o PC inicial, sequência de instruções, checkpoints, exceção, COP0 e TLB.
- Determinar se o bootcode está executando, se está preso em loop ou se falha em acesso a hardware.
- Não misturar com logs da rotina IPL3 antiga.

### Passo 3 — identificar estado real necessário no handoff
- Investigar os registradores e estruturas esperados pelo bootcode de cartucho do GoldenEye.
- Comparar com comportamento/documentação confiável do IPL3/PIF/CIC e com logs históricos disponíveis.
- Determinar valores e efeitos ausentes (COP0, cache/status, RDRAM, registradores de periféricos, interrupções etc.) somente com evidência.
- Criar testes unitários pequenos para cada estado adicionado.
- Não declarar sucesso apenas por passar em uma ROM sintética.

### Passo 4 — implementar HLE dos serviços que faltam
- Projetar PIF/SI/Joybus HLE e protocolo CIC conforme necessário.
- Completar DMA/registradores e interrupções do hardware que o bootcode realmente acessa.
- Registrar cada serviço HLE simulado e sua limitação nos logs.
- Manter o caminho IPL3 legado apenas como diagnóstico comparativo, sem confundir com HLE.

### Passo 5 — avançar para execução de jogo e imagem
- Completar CPU R4300/MIPS III conforme instruções exigidas pelos logs.
- Implementar/integrar RSP e RDP próprios e VI/framebuffer.
- Adicionar áudio e controles depois que a execução básica estiver estável.
- Testar GoldenEye em aparelho Android e guardar logs/imagens reproduzíveis.
- Preservar UI portrait e botão copiar log dentro da tela de diagnóstico.

## 9. Como o próximo chat deve começar

A primeira ação do próximo chat deve ser ler este documento e conferir o HEAD atual da branch `dev/n64-clean-start`. Depois, consultar o resultado da build #592 (ou build mais recente) e inspecionar os arquivos citados.

**Não começar reescrevendo o projeto. Não alterar `main`. Não dizer que o jogo já funciona.**

Mensagem de arranque recomendada para o próximo chat:

> Continue o projeto N64 Android a partir do documento `docs/CONTINUIDADE_CHAT.md` no GitHub. Leia o documento inteiro, confira o HEAD real da branch `dev/n64-clean-start` e os arquivos atuais antes de agir. Não altere a branch `main`. Nosso objetivo é avançar o HLE sem executar IPL3/PIF ROM original e fazer o GoldenEye avançar. Comece verificando a build mais recente e depois analise o primeiro log real do novo HLE. Atualize `docs/CONTINUIDADE_CHAT.md` a cada avanço relevante para que o próximo chat nunca perca o contexto.

## 10. Histórico de atualização deste documento

- **2026-10-31:** criado documento inicial de passagem de contexto; registrado handoff HLE experimental, auditoria, testes, limitações, branch/commit e build #592.
- **2026-10-31:** diagnóstico HLE ampliado com eventos PI/SP DMA, escritas RDRAM, estado dos registradores SI e snapshot PIF RAM; build #595 concluída com sucesso e APK de diagnóstico gerada.
- **2026-10-31:** após o primeiro log real HLE (125.556 instruções, falha em leitura física 0x70000510), o catch do diagnóstico foi ampliado para preservar instrução, acesso, registradores, COP0/TLB e barramento; commit `227040724945b051343dce2ab9d6aed41b9e3088`; build #598 em fila.

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
