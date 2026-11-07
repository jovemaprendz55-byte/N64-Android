# Integração da RDRAM nativa ao handoff HLE — 2026-11-07

Branch: `dev/n64-hle-clean-reboot`

## Alterações

- `MainActivity` agora cria uma `N64NativeEngine` ao preparar o handoff HLE.
- A engine precisa reportar API nativa versão 4 antes de ser usada.
- `N64HleNativeRdramStorage` é injetado em `N64HleCartridgeSession.prepareHleHandoff`.
- O payload da ROM e as estruturas HLE passam a ser escritos na RDRAM nativa de 8 MiB por meio das transferências JNI em bloco.
- DMEM, IMEM e MMIO continuam no espaço de endereçamento Kotlin; esta alteração não migra essas regiões para C++.
- A Activity é dona da engine. Ao trocar ROM ou destruir a Activity, fecha a engine.
- Chamadas públicas da engine nativa e `close()` são sincronizadas para impedir destruição concorrente do handle durante uma chamada JNI.

## Limites

- A CPU R4300 continua sendo o interpretador Kotlin existente.
- Esta integração não implementa IPL3/PIF, RSP, RDP, VI, áudio, scheduler ou temporização de console.
- O contador de instruções de uma sessão recém-criada continua começando em zero. Lotes executados anteriormente pertencem a uma sessão anterior e não são preservados ao preparar novo handoff.
- Não foi declarado boot funcional de GoldenEye. A próxima validação deve demonstrar que o handoff e a execução limitada leem instruções corretamente da RDRAM nativa no aparelho.

## Validação pendente

- Aguardar GitHub Actions para compilação, testes unitários, testes JNI instrumentados e APK.
- Depois da CI, instalar o novo APK no Poco C85, selecionar a ROM legal do usuário, preparar HLE e executar um lote curto de diagnóstico. Comparar PC, palavra da instrução e contagem com o caminho previamente observado.
- Qualquer falha deve ser registrada com o PC e a mensagem exatos; não mascarar exceções nem fabricar estado do hardware.

## Licenciamento

Implementação própria. Nenhum código GPLv2 do Mupen64Plus foi incorporado, copiado ou adaptado.
