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
