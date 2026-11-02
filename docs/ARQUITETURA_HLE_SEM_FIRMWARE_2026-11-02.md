# Arquitetura HLE sem firmware externo — 2026-11-02

## Decisão do projeto

O N64-Android deve iniciar e executar jogos sem exigir arquivos externos de firmware/BIOS da Nintendo 64. Não exigir nem distribuir IPL3.bin, PIF ROM ou dumps de BIOS.

O caminho principal será HLE: implementar no próprio emulador os efeitos de inicialização e os serviços do sistema que um jogo espera encontrar.

## Distinção importante: firmware externo x dados dentro da ROM

Uma ROM de cartucho contém um cabeçalho e uma área IPL3 nos primeiros 4 KiB. O HLE atual lê a área IPL3 apenas para calcular uma soma de palavras e reconhecer um pequeno conjunto de CICs. Ele não copia IPL3 para SP IMEM/DMEM e não executa suas instruções.

Portanto:
- Não é necessário um arquivo separado de firmware.
- A ROM de cartucho continua contendo os dados originais do seu próprio cabeçalho/área IPL3.
- Não remover nem substituir arbitrariamente esses bytes da ROM.
- O cálculo atual reconhece somente CIC 6102/7101 (seed 0x3F) e CIC 6105/7105 (seed 0x91). Outros CICs ainda são rejeitados; não adivinhar seed.

## Estado confirmado no código

### Boot HLE existente

`N64HleBoot.prepare()`:
- Não usa PIF ROM (`pifRomUsed=false`).
- Lê o entry point do cabeçalho.
- Reconhece o CIC pela soma da área IPL3 para os dois grupos suportados.
- Copia 1 MiB da ROM (offset 0x1000) para RDRAM (0x400).
- Preenche parâmetros de inicialização de baixa memória.
- Inicializa registradores selecionados e PC/stack da CPU.
- Não executa IPL3.

`N64HleBootTest` já tem teste com `pifRom=null`, verifica que o boot funciona nesse cenário e que IPL3 não foi colocado em SP DMEM. Também testa CIC 6105 e rejeição de CIC desconhecido sem alterar o estado da CPU.

### HLE de sistema existente

`N64HleOsKernel` é somente um modelo host-side inicial. Não é ainda uma implementação ABI de libultra acessível pelo código guest. Não intercepta chamadas da ROM, não lê/escreve OSThread/OSMesgQueue na RDRAM e não troca contexto real da CPU.

## O que ainda falta para um HLE sem firmware funcional

1. **PIF/Joybus HLE:** implementar protocolo de comandos em PIF RAM para controles, identificação e respostas de dispositivos. Não depender de PIF ROM externa.
2. **CIC coverage:** ampliar a identificação de CIC por dados de ROM conhecidos e testes verificáveis. Não escolher seed por tentativa aleatória.
3. **Contexto R4300:** salvar/restaurar GPR, FPR/FCR, HI/LO, COP0, TLB, PC e estado de branch/delay slot, com testes de ida e volta.
4. **ABI guest de libultra:** layouts corretos de OSThread, filas/eventos e chamadas de sistema, validados por referências técnicas e testes de memória.
5. **Exceções e interrupções:** ligar eventos reais dos dispositivos ao mecanismo arquitetural de interrupção, sem fabricar MI/VI/SP interrupts.
6. **Inicialização de dispositivos:** completar o comportamento HLE necessário de RDRAM/RI, SP, PI, SI, VI e AI conforme as ROMs exigirem.
7. **RSP/RDP e áudio:** completar instruções e comandos usados pelos jogos, mantendo os comandos não implementados explícitos.
8. **Compatibilidade:** testar jogos diferentes, registrar o CIC reconhecido, chamadas HLE, exceções, DMA e resultados de vídeo/áudio.

## Regras de implementação

- Nunca exigir firmware externo para o fluxo normal.
- Nunca copiar/rodar IPL3 ou PIF ROM original como atalho.
- Não fabricar OSTask, interrupções, DMA ou estado de registrador para fazer a ROM avançar.
- Não esconder exceções ou forçar SP HALT.
- Não declarar suporte universal com base em um único jogo.
- Distinguir sempre: confirmado no código, observado em log, hipótese e ainda não testado.
- A ausência de firmware externo não significa ausência de trabalho de emulação: HLE precisa reproduzir os serviços e efeitos esperados.

## Próxima etapa técnica recomendada

Antes de integrar o kernel host-side à ROM, implementar e testar snapshots/restauração completos do contexto R4300 e snapshot/restauração do TLB. Depois definir a ABI guest de OSThread com offsets confirmados; não inventar layout nem iniciar context switch parcial.
