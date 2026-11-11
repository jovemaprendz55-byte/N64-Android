# GoldenEye: V-Sync ligado sem frame skip adaptativo

Teste isolado para comparar a apresentação do GoldenEye no Poco C85.

- Mantém `config.video.verticalSync = 1`.
- Mantém áudio, resolução e demais configurações iguais ao teste anterior.
- Remove somente o patch experimental de apresentação adaptativa em `VI.cpp`.
- Não altera o ramo principal e não faz merge.

Objetivo: verificar se o mecanismo adaptativo de pular apresentações contribui para oscilações de FPS e frame pacing.
