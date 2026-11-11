# GoldenEye: EGL swap interval forçado em zero

Teste diagnóstico isolado após o aparelho registrar `EGL swap interval aplicado: 1` mesmo na build configurada com `config.video.verticalSync = 0`.

- Mantém o PR #11 como base: sem frame skip adaptativo e com a cadência VI normal.
- Mantém áudio, resolução, renderer e demais configurações.
- Registra o valor solicitado pelo GLideN64.
- Força `eglSwapInterval(display, 0)` na extensão EGL Android e registra o valor efetivamente aplicado.

Objetivo: confirmar se o EGL estava continuando sincronizado apesar da configuração V-Sync OFF. Esta é uma build exclusivamente diagnóstica; não mesclar sem teste no Poco C85 e comparação da fluidez, áudio, `work_avg_ms` e `swap_avg_ms`.
