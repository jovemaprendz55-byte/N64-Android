# Auditoria de integração do CoreCallGate — 2026-11-09

## Estado verificado

Esta auditoria foi feita sobre a branch `main`, após o protótipo isolado de `CoreCallGate`.

- `core_call_gate.h` existe e implementa leases para Execute, Stop e Other.
- `tests/native/core_call_gate_test.cpp` testa fechamento de admissão, entrada de STOP durante shutdown, drenagem das leases e reabertura após drenagem.
- O gate **ainda não está incluído nem utilizado por `native-lib.cpp`**.
- `nativeExecuteCore()` continua retornando antes de qualquer `M64CMD_EXECUTE`. A proteção não deve ser removida nesta etapa.

## Pontos confirmados no código atual

1. `nativeStopCore()` mantém `g_coreMutex` durante `dlsym` e a chamada síncrona a `CoreDoCommand(M64CMD_STOP)`.
2. `nativeShutdown()` mantém `g_coreMutex` durante detach dos plugins, fechamento da ROM, `PluginShutdown`, `CoreShutdown` e `dlclose`.
3. `nativeOpenRomAndAttachGfx()`, carregamento/inicialização dos plugins e outras operações também usam o mutex global. A integração precisa cobrir todas as operações que usam handles ou ponteiros de função, não somente Execute.
4. `M64CMD_STOP` é assíncrono: seu retorno não comprova que o loop síncrono de Execute terminou.
5. `MainActivity.onPause()` chama `glView?.onPause()` imediatamente; não solicita STOP nem aguarda o retorno de Execute.
6. `MainActivity.onDestroy()` agenda shutdown na thread GL e aguarda até 1500 ms. Timeout não comprova que o callback terminou; nesse caso, bibliotecas e recursos devem permanecer carregados.
7. O caminho de Execute permanece sem chamada alcançável para `M64CMD_EXECUTE`; portanto, não há confirmação de primeira tarefa RSP, processamento de lista gráfica ou frame real do jogo.

## Protocolo necessário antes de habilitar Execute

1. Fechar a admissão de novas operações Execute/Other.
2. Permitir uma lease Stop mesmo depois do fechamento.
3. Adquirir a lease Stop, proteger a vida útil do handle do Core e chamar `M64CMD_STOP` sem manter um mutex que impeça STOP de alcançar o Core.
4. Liberar a lease Stop e aguardar a drenagem de todas as leases, sem segurar o mutex necessário à operação STOP.
5. Somente após a drenagem, fazer detach, fechar ROM, encerrar plugins/Core e descarregar bibliotecas.
6. Se a drenagem ou o encerramento exceder o prazo, preservar handles e bibliotecas; nunca tratar timeout como encerramento concluído.
7. Garantir que operações de vídeo/destruição de recursos GL ocorram na thread com o contexto EGL atual.
8. Em `onPause()`, solicitar parada e aguardar o retorno efetivo de Execute antes de pausar a superfície GL.
9. Cobrir transições concorrentes de initialize, start, carregamento de plugins, abertura de ROM, registro de Video Extension, detach, shutdown e unload.

## Regra de segurança

Não integrar o gate parcialmente e não habilitar `M64CMD_EXECUTE` enquanto operações concorrentes ainda puderem usar handles durante `dlclose`, ou enquanto o ciclo Android pause/destroy não aguardar o retorno real de Execute.

## Próxima implementação recomendada

Projetar e testar um coordenador de runtime que combine admissão de leases e proteção da vida útil dos handles. Primeiro criar testes concorrentes independentes (Execute vs STOP vs shutdown, chamadas Other vs fechamento de admissão, timeout sem unload e falha de teardown); depois integrar cada ponto JNI sob esse protocolo. Manter a execução bloqueada até que a integração e os testes ARM64/Android estejam concluídos.

## Achados adicionais da revisão de código — 2026-11-09

A inspeção detalhada de native-lib.cpp e MainActivity.kt confirmou outros pontos que precisam entrar no desenho do coordenador:

10. nativeStartCore() mantém g_coreMutex enquanto chama CoreStartup. A chamada externa ao Core não deve ficar protegida por um mutex que também seja necessário ao STOP ou à finalização; a lease precisa proteger o handle enquanto o lock de estado é liberado.
11. nativeRegisterAndroidVideo(), nativeLoadGfxPlugin(), nativeLoadRspPlugin() e nativeOpenRomAndAttachGfx() também mantêm o mutex global enquanto chamam APIs externas do Core ou dos plugins. Todas precisam de lease Other (e validação de estado) antes de liberar o mutex em torno das chamadas externas.
12. nativeOpenRomAndAttachGfx() executa M64CMD_ROM_OPEN e CoreAttachPlugin enquanto mantém o mutex. A ordem parcial (ROM aberta, GFX anexado, RSP ainda não anexado) precisa ser representada explicitamente para permitir retry seguro sem duplicar operações.
13. showHomeScreen() remove a GLSurfaceView da hierarquia e chama onPause() sem primeiro encerrar/desanexar o GLideN64 ou limpar a Video Extension. O mesmo padrão aparece quando showOpenGlTest() substitui uma superfície existente. Isso pode deixar o plugin dependente de um contexto EGL que já não está mais ativo.
14. onDestroy() agenda nativeShutdown() na thread GL e aguarda 1500 ms, mas não pede STOP antes. Como Execute está atualmente bloqueado, isso não provoca o bloqueio hoje; quando Execute for habilitado, o ciclo de vida precisará primeiro solicitar STOP e esperar o retorno real do Execute antes de agendar teardown gráfico.
15. executeCoreOnGlThread() usa queueEvent e chama nativeExecuteCore() de forma síncrona na GL thread. Se a execução real bloquear nessa thread, callbacks posteriores enfileirados nela (incluindo teardown) não poderão executar até o Core retornar. O mecanismo STOP deve, portanto, ser chamado por uma rota concorrente que não dependa da GL thread bloqueada, mas mantendo lease e handle vivos.
16. showHomeScreen() e a substituição da superfície são caminhos de saída adicionais além de onPause()/onDestroy(). Devem compartilhar uma rotina de transição de estado; não podem simplesmente destruir ou pausar a superfície enquanto o plugin gráfico ainda está ativo.

### Consequência para a integração

A próxima etapa não é apenas adicionar CoreCallGate a nativeExecuteCore() e nativeStopCore(). É necessário um coordenador de runtime que controle o estado e a vida útil dos handles, com operações externas executadas fora do mutex de estado sob leases, além de uma única rotina Android para parar, drenar, desmontar plugins e só então pausar/remover a superfície EGL. A implementação deve começar com testes concorrentes do coordenador e só depois substituir os caminhos JNI atuais.

## Achado adicional — admissão duplicada de Execute

A primeira versão do `CoreRuntimeCoordinator` permitia que duas chamadas `tryAcquire(Execute)` obtivessem leases enquanto a fase ainda era `PluginsAttached`, antes de qualquer uma chamar `markExecuting()`. Isso poderia permitir duas tentativas concorrentes de entrar no loop síncrono do Core.

O coordenador agora introduz `ExecuteReserved`: a primeira lease de Execute reserva atomicamente a vaga e chamadas seguintes são recusadas. `markExecuting()` só aceita a reserva enquanto a admissão continua aberta. Se o shutdown vencer antes do início, `markExecuteNotStarted()` preserva `StopRequested`, e `requiresStopCommand()` indica que não se deve enviar `M64CMD_STOP` para um loop que nunca começou. Se Execute já entrou, shutdown registra que STOP é necessário. Os testes cobrem os dois lados dessa corrida.

Este ajuste continua sendo um protótipo independente. Ainda não protege os ponteiros/handles JNI e não foi integrado a `native-lib.cpp`; a execução permanece bloqueada.

## Restrição adicional de chamadas concorrentes

A admissão `Other` agora fica limitada às fases `CoreInitialized`, `RomOpen` e `PluginsAttached`. Ela é recusada durante `ExecuteReserved` e `Executing`, evitando que uma chamada JNI de controle entre no Core enquanto o loop síncrono de emulação está ativo. Os testes cobrem a recusa nas duas fases. A integração JNI ainda deverá mapear cada função concreta para a operação apropriada e manter leases até o último uso do handle.


## Integração JNI — primeiro estágio (2026-11-09)

O CoreRuntimeCoordinator deixou de ser apenas um protótipo de teste: native-lib.cpp agora mantém uma instância global e adquire leases Other antes de entrar no mutex/usar o Core nas rotinas de inicialização da biblioteca, CoreStartup, teste/registro da Video Extension, carregamento/inicialização dos plugins GFX e RSP e abertura/anexação da ROM.

As transições são atualizadas após sucesso:
- CoreStartup -> CoreInitialized
- M64CMD_ROM_OPEN -> RomOpen
- GFX e RSP anexados -> PluginsAttached

O encerramento agora fecha a admissão, emite STOP protegido se existir uma fase Executing, espera o drain por até 1500 ms e só então adquire g_coreMutex para detach/ROM close/PluginShutdown/CoreShutdown/dlclose. Timeout mantém todos os handles e bibliotecas. Se uma etapa do teardown falhar após o drain, a fase Drained permite repetir o encerramento sem tentar reabrir operações normais. Depois do teardown integral, resetAfterShutdown() permite um novo ciclo.

nativeStopCore agora usa uma lease STOP e não mantém g_coreMutex durante a chamada assíncrona ao Core. Um atomic g_stopCommandIssued evita duplicar o pedido entre o botão Parar e o caminho de shutdown.

A camada Kotlin também passou a exigir que showHomeScreen() e showOpenGlTest() concluam o shutdown na thread GL antes de remover/substituir uma superfície que pode pertencer ao GLideN64. Se não for possível confirmar o teardown, a troca de tela é cancelada e a superfície atual é preservada.

### Limites que permanecem

- nativeExecuteCore() continua bloqueado por safety gate; nenhum código de execução foi liberado.
- Como Execute continua bloqueado, ainda não existe teste real no dispositivo de Execute/STOP simultâneos, drain do loop síncrono ou retorno de M64CMD_EXECUTE.
- O callback onPause() ainda precisa de uma política explícita de pausa/retomada para o caso de execução real.
- Não há plugin real de áudio/input e não se deve declarar o emulador funcional.
- A aprovação do CI valida compilação/testes automatizados, não a renderização de frames no Poco C85.
