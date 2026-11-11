# GoldenEye: V-Sync desligado sem frame skip adaptativo

Teste isolado para comparar com a build V-Sync ligada, sem mecanismo adaptativo de pular apresentações.

- Define `config.video.verticalSync = 0`.
- Mantém a cadência VI normal (`bsOnVerticalInterrupt`).
- Mantém áudio, resolução, ROM e demais configurações.
- Não altera o ramo principal e não faz merge.

Objetivo: verificar se as oscilações de `swap_avg_ms` e FPS observadas com V-Sync ligado desaparecem ao desligá-lo.
