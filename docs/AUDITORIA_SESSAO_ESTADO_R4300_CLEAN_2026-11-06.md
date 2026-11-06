# Auditoria da sessão de execução e estado arquitetural — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: somente `app/src/clean/java`, `app/src/cleanTest/java` e `docs/`.

## 1. Resumo

A sessão de execução é deliberadamente limitada: o chamador escolhe um orçamento finito por bloco, com máximo de 4096 instruções. Não existe loop infinito ou thread automática nesta classe. A sessão executa por fetch de memória através do stepper, conta passos concluídos com sucesso e mantém diagnóstico da última tentativa.

O estado da CPU, por sua vez, não inventa valores de reset. GPRs, HI/LO e COP0 podem permanecer desconhecidos até que sejam explicitamente inicializados ou escritos. Essa política evita simular um estado inicial que não foi provado, mas exige que os testes diferenciem “valor zero” de “valor ainda desconhecido”.

## 2. Comportamento confirmado da sessão

Arquivo: `N64HleR4300ExecutionSession.kt`.

- `runChunk()` aceita somente orçamento entre 1 e 4096.
- Cada passo usa `stepFromMemory()`; a sessão não executa palavras de instrução fornecidas diretamente.
- O contador total e o contador do bloco só aumentam depois que `stepFromMemory()` retorna com sucesso.
- Em falha, registra PC tentado, tipo e mensagem da exceção, e relança a mesma exceção.
- A última instrução bem-sucedida é preservada quando uma tentativa posterior falha.
- A sessão não transforma falha em sucesso e não oculta exceções arquiteturais.

Testes existentes em `N64HleR4300ExecutionSessionTest.kt` cobrem:
- execução em blocos de um passo e continuidade no bloco seguinte;
- rejeição de orçamento zero e acima do limite;
- falha de fetch sem incremento do contador;
- preservação do último sucesso quando o fetch seguinte falha.

## 3. Limitações de diagnóstico da sessão

O contador `totalExecutedInstructions` conta passos que retornaram sucesso, incluindo a etapa de agendamento de uma transferência de controle e a execução do delay slot como outro passo. É uma contagem de instruções concluídas pelo stepper, não de ciclos de hardware nem de instruções aposentadas por um pipeline real.

A sessão não oferece:
- orçamento em ciclos;
- interrupções assíncronas;
- pausa/resume entre operações internas de uma instrução;
- estado de pipeline;
- telemetria de RSP/RDP/VI;
- snapshot completo e serializável de todos os componentes da máquina.

O nome `lastSuccessfulInstructionPc` representa o PC antes do passo bem-sucedido. Em uma instrução de branch/jump, o stepper pode ter agendado o delay slot; isso não equivale a ter executado o delay slot ainda.

## 4. Estado arquitetural conhecido versus desconhecido

Arquivo: `N64HleR4300CpuState.kt`.

- GPR0 é sempre zero e escritas nele são descartadas.
- GPR1..31 só podem ser lidos depois de conhecidos.
- HI e LO começam desconhecidos por padrão.
- COP0 mantém bitmap de registradores conhecidos; leitura de registrador desconhecido falha explicitamente.
- COP0 é armazenado em valores de 32 bits; os valores conhecidos da entrada HLE são copiados.
- `copyKnownGprs()` e `copyKnownCop0()` retornam mapas defensivos dos valores conhecidos.

Consequência: zero não deve ser usado como substituto de “desconhecido”. Um teste que verifica apenas o valor lido não consegue provar a preservação do estado se o registrador ainda não era conhecido; deve verificar também `isGprKnown`, `isCop0Known`, `isHiKnown` e `isLoKnown`.

## 5. Controle de fluxo e delay slot

Arquivos:
- `N64HleR4300ControlTransferPlanner.kt`
- `N64HleR4300ControlFlowSequencer.kt`

O planner descreve o endereço do delay slot e o destino final; o sequencer aplica o link (quando presente), posiciona PC no delay slot e só instala o destino final depois que o chamador conclui o slot.

O sequencer é explicitamente uma primitiva, não um stepper completo. Exceções precisam ser coordenadas pelo stepper. O stepper atual:
- rejeita uma transferência de controle dentro de delay slot por precondição;
- cancela a pendência ao concluir entrada em exceção arquitetural tratada;
- conclui o delay slot somente depois de a instrução do slot retornar sem falha.

A futura API de resultados tipados precisa preservar essa ordem. Um resultado “válida não implementada” não pode chamar `completeDelaySlot()`.

## 6. Testes adicionais recomendados

1. Executar um chunk com branch e delay slot e verificar que são contados como dois passos concluídos.
2. Executar uma instrução não suportada buscada da memória: registrar falha, não incrementar total, preservar a última instrução bem-sucedida.
3. Testar tentativa não suportada em delay slot: preservar PC e pendência, sem marcar instrução como concluída.
4. Fazer snapshot de conhecimento e valores de GPR, HI/LO e COP0 antes/depois de instrução sem suporte.
5. Testar orçamento máximo 4096 e confirmar que não há execução acima do limite.
6. Diferenciar falha de fetch, falha de execução, exceção arquitetural e falta de capacidade nos diagnósticos.

## 7. Status de validação

- **Confirmado por inspeção estática:** limites de orçamento, contagem após sucesso, propagação de exceção, semântica de valores desconhecidos e sequência de delay slot.
- **Confirmado por leitura dos testes-fonte:** quatro cenários de sessão citados na seção 2.
- **Não executado nesta auditoria:** Gradle, cleanTest ou execução no dispositivo.
- **Não alterado:** código de produção e testes.
- **Não comprovado:** ciclo-accurate, pipeline real, boot completo ou jogo funcional.

## 8. Próxima etapa

Após a correção tipada do despacho R4300, ampliar os testes da sessão para instruções não suportadas buscadas da memória e falhas em delay slot. Antes disso, não declarar a sessão como motor de emulação de jogo: ela é atualmente um driver limitado para o subconjunto implementado.

---
Relatório exclusivo da arquitetura limpa. Não usar código da versão antiga como fonte de migração.
