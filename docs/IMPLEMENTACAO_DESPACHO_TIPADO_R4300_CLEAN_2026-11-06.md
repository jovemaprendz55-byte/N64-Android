# Implementação inicial do despacho tipado R4300 — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
HEAD da implementação: `9089e664768ff78f6eac3106f6954b2afd831f75`

## Escopo

Somente a arquitetura limpa: `app/src/clean/java`, `app/src/cleanTest/java` e `docs/`. Nenhum código legado foi usado. A branch `main` não foi alterada.

## Alterações

- Adicionado `N64HleR4300StepResult.kt` com resultados tipados: Executed, ArchitecturalException, ReservedEncoding, ValidButNotImplemented e UnclassifiedEncoding.
- Adicionado `stepWithResult()` e `stepFromMemoryWithResult()` ao stepper, preservando as APIs antigas `step()` e `stepFromMemory()`.
- O fallback genérico para `immediateExecutor.execute()` foi removido. O stepper agora seleciona o executor por capacidade explícita.
- Adicionado `supports()` ao executor SPECIAL, baseado nas operações realmente implementadas.
- COP0 DMFC0/DMTC0, operações COP0 CO reconhecidas porém ainda sem executor, SPECIAL SYNC e LWC1/SWC1 são reportadas como válidas não implementadas.
- Codificações que o classificador já identifica como RI continuam entrando na exceção arquitetural; a nova API expõe isso como ReservedEncoding.
- Encodings sem classificação suficiente retornam UnclassifiedEncoding; não são presumidos como RI nem como instruções válidas.
- O tipo de exceção de instrução sem suporte herda de IllegalArgumentException para preservar compatibilidade com testes e chamadas existentes.

## Garantia pretendida e limites

- O despacho identifica falta de capacidade antes de chamar um executor inadequado.
- Para os casos de falta de executor cobertos aqui, o stepper não avança PC nem conclui um delay slot pendente.
- Esta mudança não implementa COP1/FPU, DMFC0/DMTC0, TLB, ERET, WAIT ou sincronização de barramento.
- Não declara CPU completa, boot completo, GoldenEye funcional ou execução no aparelho.

## Testes adicionados/atualizados

- Resultado válido não implementado para LWC1 sem avanço de PC.
- Resultado válido não implementado para DMFC0 sem mutação de GPR/PC.
- SPECIAL SYNC reconhecida como válida não implementada.
- Opcode ainda não classificado não é promovido a RI.
- Codificação REGIMM reservada continua sendo RI.
- Instrução válida não implementada no delay slot preserva PC e transferência pendente.

## Validação

- Commits de implementação: `cd311ae`, `027e3fb`, `5cb0ed1`, `4edf686`, `7f58c43`, `9089e66`.
- CI confirmada: o workflow [42186546728](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42186546728), no commit `9089e66`, concluiu `success`; o log registra `:app:compileDebugKotlin`, `:app:testDebugUnitTest` e `BUILD SUCCESSFUL`. O commit `027e3fb` intermediário falhou porque a classe de resultado foi adicionada no commit seguinte; a versão integrada no HEAD `9089e66` passou.
- Nenhuma execução em aparelho foi realizada nesta etapa.

## Próximo passo

1. Conferir a CI do HEAD `9089e66` e corrigir qualquer falha.
2. Ampliar a classificação explícita para os formatos de memória válidos sem barramento configurado, sem adivinhar codificações reservadas.
3. Adicionar snapshot de estado arquitetural mais completo para GPR, HI/LO, COP0 e delay-slot.
4. Depois da aprovação, avançar para COP0/TLB e MMU em etapas pequenas e testáveis.


## Complemento: classificação de memória e snapshot integral — 2026-11-06

HEAD desta validação: `01df520d2af71c9bf6c81db340b94bd8ca5a6580`.

- O stepper agora classifica explicitamente formatos de memória inteira conhecidos como `VALID_BUT_NOT_IMPLEMENTED` quando nenhum executor de memória está configurado. Isso evita confundir instruções válidas com encodings desconhecidos.
- Adicionados testes para LW sem barramento configurado e preservação de PC, GPRs conhecidos, COP0 conhecido, HI/LO e estado do delay slot diante de LWC1 ainda não implementada.
- O teste que verificava opcode desconhecido usava `0x3F`, que é o opcode válido `SD`; por isso falhava corretamente ao encontrar a classificação válida. O fixture foi corrigido para `0x3E`, que permanece sem classificação no subconjunto atual. Nenhuma regra de produção foi enfraquecida.
- CI final aprovada no workflow [42187019787](https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42187019787), commit `01df520`: job `build` success, incluindo `Build and test` e `Upload debug APK`.
- Os workflows intermediários `42186762498` e `42186767528` falharam devido ao fixture `0x3F`; a correção `01df520` passou. A validação não é teste em dispositivo nem execução de GoldenEye.

## Auditoria do caminho até GoldenEye — 2026-11-06

Arquivos examinados na branch limpa: `N64HleBootProfile.kt`, `N64HleBootProfileFactory.kt`, `N64HleCartridgeImage.kt`, `N64HleCartridgeSession.kt`, `N64HleGuestAddressSpace.kt`, `N64HleR4300ExecutionSession.kt`, `MainActivity.kt` e os testes correspondentes de boot, sessão, cartucho e memória.

Achados verificáveis:
- A interface Android seleciona e identifica ROM, normaliza byte order e mantém sessão; ela mesma informa que vídeo e áudio ainda não estão conectados à tela.
- O perfil automático de boot existente é deliberadamente limitado a CIC 6102/7101, valida checksum de IPL3 e CRC1/CRC2 de imagem de 1 MiB, prepara parâmetros e entrega um snapshot HLE. Não executa IPL3/PIF nem inicializa o hardware real.
- O factory rejeita CIC 6105/7105; portanto, não se deve assumir que o perfil atual atende GoldenEye sem identificar a revisão/região e o CIC real da ROM legal do usuário.
- O espaço de endereços cobre RDRAM, SP DMEM/IMEM e parte dos registradores SP/DMA; MMIO geral, TLB e dispositivos restantes não estão implementados nessa abstração.
- A sessão R4300 executa blocos limitados de instruções buscadas na memória, mas ainda não constitui runtime de console completo. Não há integração demonstrada de RSP, RDP, VI/framebuffer, áudio ou controles de jogo.
- Testes atuais de boot usam fixtures sintéticos CIC 6102; não são evidência de compatibilidade com GoldenEye.

Decisão: antes de ampliar instruções isoladas sem relação demonstrada com o primeiro jogo, o próximo bloco deve identificar com segurança o CIC/revisão do GoldenEye alvo e mapear as lacunas de boot/runtime que impedem o jogo. Depois, implementar o caminho mínimo correto para o CIC correspondente, sem introduzir constantes presumidas. Nenhuma alteração de código de produção foi feita nesta auditoria.
