# Plano de MVP: executar o primeiro jogo N64

Data: 2026-11-03  
Branch: `dev/n64-clean-start`  
Objetivo: obter a primeira execução real de um jogo em ROM, priorizando o caminho mínimo funcional. Depois do primeiro jogo, retomar a expansão de compatibilidade e precisão.

## Regra de prioridade
Não ampliar a cobertura de instruções R4300/COP1 por perfeccionismo enquanto houver bloqueios mais próximos do primeiro quadro do jogo. Corrigir CPU quando o jogo-alvo ou um teste de integração demonstrar que a instrução impede o boot.

## Estado já existente (inspecionado no código)
- `N64RomLoader`: reconhece Z64/V64/N64, normaliza byte order e limita ROM a 64 MiB.
- `N64HleBoot`: reconhece seeds CIC suportados, prepara parâmetros básicos e copia 1 MiB da ROM (offset 0x1000) para RDRAM 0x400; não executa IPL3/PIF e não modela toda inicialização de hardware.
- `N64RomVideoSession`: executa CPU continuamente, monitora VI e faz polling cooperativo do RSP.
- `N64ViFramebufferReader` e `N64VideoPresenterView`: convertem framebuffer VI RGBA5551/RGBA8888 e mostram Bitmap na interface Android.
- `N64DpcCommandProcessor`, parser RDP e rasterizadores: há infraestrutura inicial de comandos gráficos, mas não se deve presumir renderização completa de microcódigo comercial.
- Limitação crítica: a sessão não descobre/agenda automaticamente OSTasks libultra; o RSP só recebe quantum quando SP HALT é liberado. Áudio, timing e vários caminhos gráficos permanecem incompletos.

## Sequência de implementação orientada ao primeiro jogo
1. **Escolher ROM-alvo concreta**: obter nome/região e informações do cabeçalho; verificar formato, tamanho, entry point e CIC suportado. Não armazenar nem distribuir ROM protegida.
2. **Criar diagnóstico de boot único**: após HLE, confirmar PC inicial, instruções executadas, exceções, progresso de SP/DPC/VI e estado do framebuffer.
3. **Fechar o caminho de tarefa gráfica**: rastrear escrita de SP_STATUS, SP_PC, DMA e DPC_START/END; reconhecer o início/fim de OSTask e despachar o RSP com orçamento limitado sem travar CPU.
4. **Primeiro quadro**: priorizar o microcódigo usado pelo jogo-alvo e os comandos RDP que ele realmente emite. Começar com clear/fill e triângulos; adicionar textura/combiner conforme o trace exigir.
5. **Exibir e manter estável**: publicar VI frame sem bloquear o worker; controlar FPS, limite de instruções e logs.
6. **Teste no aparelho**: instalar APK, selecionar a ROM legal do usuário e coletar logs. Só chamar de jogo executando quando houver evidência no dispositivo; CI isolada não prova isso.

## Critério de sucesso do MVP
- ROM reconhecida e normalizada.
- HLE passa pelo entry point sem loop imediato de exceção.
- Pelo menos uma OSTask é identificada e executada.
- RDP produz framebuffer que VI consegue ler.
- Um quadro real do jogo aparece no Galaxy A15 Android 16. Um padrão de teste ou framebuffer sintético não conta como jogo funcionando.

## Perfil do jogo-alvo: GoldenEye 007

- Jogo escolhido pelo usuário em 2026-11-03: **GoldenEye 007 (N64)**.
- O código atual reconhece o seed CIC `0x3F` associado ao CIC-NUS-6102/7101 e comenta seu uso na versão USA de GoldenEye. Isso é apenas reconhecimento de CIC; não prova que o boot do jogo esteja funcional.
- Referências técnicas públicas sobre a descompilação do jogo indicam uma variante de F3D/RSP da Rare, com extensão de triângulos em lote **G_TRI4 (opcode 0xB1)**. Não tratar GoldenEye como teste garantido de F3DEX padrão. Confirmar a implementação com microcódigo/display lists da ROM e traces do emulador.
- Prioridade específica: validar cabeçalho/região/CIC da ROM do usuário; identificar OSTask e endereço de display list; identificar microcódigo carregado; então implementar o mínimo de F3D e G_TRI4 necessário, seguido dos comandos RDP realmente observados.
- Não distribuir nem pedir upload da ROM protegida. O usuário pode selecionar localmente uma cópia obtida legalmente e compartilhar apenas o resumo do cabeçalho/logs.
- Região/edição da ROM ainda não informada. Não presumir USA; verificar o seed `0x3F` contra a cópia concreta.

## O que fica temporariamente em segundo plano
- Cobertura exaustiva de instruções COP1 e exceções IEEE-754.
- Precisão ciclo-a-ciclo de Count/VI/AI.
- Áudio completo, 64DD e compatibilidade ampla com vários jogos.
- Refatorações sem relação direta com o primeiro quadro.

## Próxima ação
Solicitar a região/edição da ROM GoldenEye 007 (USA, Europe/PAL ou Japan) e, quando disponível, o resumo do cabeçalho exportado pelo próprio app. Em paralelo, auditar o caminho SP DMA → OSTask → RSP e criar testes de reconhecimento de tarefas sem inventar descritores.
