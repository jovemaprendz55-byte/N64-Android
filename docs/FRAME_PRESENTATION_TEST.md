# Teste de apresentação de quadros por VI Origin

## Objetivo
Avaliar se o GoldenEye fica mais fluido no Poco C85 usando o modo nativo do GLideN64 que atualiza o buffer quando a origem VI muda.

## Implementação
O workflow Android aplica:
`config.frameBufferEmulation.bufferSwapMode = Config::bsOnVIOriginChange`

No GLideN64, esse modo faz o `VI_UpdateScreen()` chamar `renderBuffer()` somente quando `VI_ORIGIN` muda (ou quando há uma escrita de CPU no framebuffer, se a emulação de framebuffer está ativa). O Core N64 continua executando normalmente; não pulamos instruções da CPU, listas RSP/RDP nem processamento de áudio.

## Limitações
- Isto é redução da frequência de atualização/apresentação do buffer, não um frame-skip tradicional que descarta o processamento de um quadro.
- Pode não ajudar se o custo dominante estiver no processamento RSP/RDP ou no tempo de espera do EGL.
- O modo VI Origin Change pode causar incompatibilidade visual em jogos que não mudam a origem VI como esperado. Por isso é um teste isolado e reversível.
- A configuração anterior `copyToRDRAM = 0` permanece ativa para manter o mesmo cenário de comparação com os logs anteriores.

## Como validar
1. Instalar o APK deste teste.
2. Abrir a mesma ROM GoldenEye e repetir a mesma cena.
3. Comparar FPS proxy, `work_avg_ms`, `swap_avg_ms`, travamentos e correção visual.
4. Verificar também áudio e outros jogos antes de considerar a configuração como padrão.

Não considerar o teste aprovado apenas porque o APK compilou. O resultado depende do teste no aparelho.
