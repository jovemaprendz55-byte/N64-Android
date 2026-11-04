# GoldenEye USA — auditoria de endereços de call gates

Data: 2026-11-04

## Resultado

Não há ainda uma tabela confiável de endereços finais para interceptar as APIs libultra de GoldenEye USA. Portanto, nenhum endereço de osStartThread, osStopThread, osYieldThread, osSendMesg ou osRecvMesg foi registrado no aplicativo.

## Evidência disponível no decomp

- ge007.ld declara o início do segmento start em 0x80000400 e o segmento code em endereço virtual derivado do final do start segment, com a observação de layout 0x70000450.
- ld/lib.text.ld.inc inclui objetos libultra como createthread.o, startthread.o, stopthread.o, sendmesg.o, recvmesg.o, thread.o e yieldthread.o na composição do segmento.
- As fontes C e assembly em src/libultra/os/ permitem validar semântica de APIs, layouts e transições.
- O script linker e a ordem dos objetos não fornecem os offsets finais de cada símbolo dentro dos objetos.
- A árvore versionada consultada não contém um mapa de linker final / tabela de símbolos para a build USA. Não se pode converter a ordem dos objetos em endereços absolutos por estimativa.

## Consequência para o emulador

N64HleGuestCallDispatcher aceita somente endereços explicitamente registrados, exige uma referência de evidência e normaliza endereços guest de 32 bits para a representação sign-extended do CPU. Não possui endereços embutidos.

O hook pre-fetch do R4300 chama o dispatcher antes de buscar a instrução. Se não houver registro exato, a instrução guest é executada normalmente. Isso evita capturar acidentalmente uma função ou endereço de outra revisão/região.

## Próximos passos de resolução

1. Obter um mapa de símbolos da build GoldenEye 007 USA correspondente à ROM usada no teste, ou gerar uma build decomp com o ROM correspondente.
2. Se não houver map, derivar endereços de símbolos por disassembly/relocations com evidência reproduzível; validar cada função em bytes/instruções e segmento.
3. Registrar nome, endereço, revisão/região e referência do mapa/disassembly em documentação antes de adicionar qualquer handler.
4. Só então registrar call gates de osStartThread, osStopThread, osYieldThread, osCreateMesgQueue, osSendMesg, osRecvMesg e osJamMesg.

## Fontes

- Linker script: https://github.com/n64decomp/007/blob/master/ge007.ld
- Ordem dos objetos libultra: https://github.com/n64decomp/007/blob/master/ld/lib.text.ld.inc
- Fontes libultra: https://github.com/n64decomp/007/tree/master/src/libultra/os

Status: auditoria sem endereços finais; nenhuma API guest está interceptada ainda.
