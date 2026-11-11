# Teste de frame skip adaptativo no Android

## Objetivo
Reduzir o custo de apresentação do GLideN64 quando a composição final de um quadro fica lenta, sem pular a execução da CPU/RSP/RDP do Nintendo 64 nem o processamento de áudio.

## Implementação experimental
O workflow aplica um patch somente ao GLideN64 Android (src/VI.cpp):

- Mantém o modo normal de atualização por interrupção vertical (bsOnVerticalInterrupt).
- Mede o tempo de FrameBufferList::renderBuffer(), que inclui a composição final e a chamada de troca de buffers.
- Usa média móvel exponencial (85% da média anterior e 15% da medição atual).
- Ativa o descarte de uma apresentação a cada duas interrupções VI quando a média chega a 14 ms.
- Desativa o descarte quando a média cai para 9 ms ou menos (histerese para evitar alternância rápida).
- Depois de descartar uma apresentação, força a apresentação pendente na próxima interrupção VI.
- Em modo de emulação de framebuffer, não limpa os marcadores de buffers alterados no quadro descartado; eles permanecem pendentes até uma apresentação real.

## O que não é pulado
As listas RDP continuam sendo processadas; o Core N64 continua executando; áudio não é condicionado pelo contador de quadros apresentados. O teste atua apenas no ponto de composição/apresentação final do buffer VI.

## Logs de diagnóstico
Procurar:
- N64ANDROID_FRAME_SKIP: enabled
- N64ANDROID_FRAME_SKIP: skipped=
- N64ANDROID_FRAME_SKIP: disabled

## Riscos e validação
Este é um experimento, não uma melhoria comprovada. Pode reduzir a carga da composição final, mas não resolver gargalos em CPU/RSP/RDP. A taxa visual pode cair para aproximadamente 30 quadros apresentados por segundo quando o modo está ativo, embora o jogo e o áudio continuem executando em sua cadência normal. Testar GoldenEye na mesma cena, verificar áudio, controles, imagem e comparar os tempos work_avg_ms e swap_avg_ms.

Não mesclar na versão principal sem validação no aparelho.