# Estado atual e roteiro da versão limpa — N64 Android

Data da auditoria: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
HEAD auditado: `3da17d5f8d77e1c49d9797e0d47619fe4f40f148`  
Commit: [Document COP0 CI diagnosis and test corrections](https://github.com/jovemaprendz55-byte/N64-Android/commit/3da17d5f8d77e1c49d9797e0d47619fe4f40f148)

## 1. Regra de escopo

O desenvolvimento ativo deve considerar exclusivamente:

- Produção: `app/src/clean/java`
- Testes: `app/src/cleanTest/java`
- Documentação: `docs/`

Não portar, importar, adaptar nem usar código de outra implementação do repositório como solução. A versão limpa deve evoluir como arquitetura independente. Não alterar a branch `main), não fazer merge e não apagar branches.

## 2. Configuração de build confirmada

O arquivo `app/build.gradle.kts` define:

```kotlin
sourceSets {
    getByName("main") {
        java.setSrcDirs(listOf("src/clean/java"))
    }
    getByName("test") {
        java.setSrcDirs(listOf("src/cleanTest/java"))
    }
}
```

Portanto, esses são os diretórios efetivamente selecionados para produção e testes. Na auditoria foram listados 29 arquivos Kotlin de produção e 24 arquivos de teste nesse source set.

## 3. Estado funcional observado no código

### Implementado na versão limpa

- Leitura de ROM pelo Android Storage Access Framework.
- Reconhecimento e normalização dos byte orders Z64, V64 e N64.
- Extração dos campos principais do cabeçalho da ROM.
- Sessão de cartucho e acesso limitado aos bytes da ROM.
- Espaço de endereçamento de memória convidada.
- Perfil HLE explícito, com valores acompanhados de evidência.
- Reconhecimento do caminho CIC 6102/7101 (seed 0x3F), validação CRC1/CRC2 e preparação de handoff.
- Estado básico da CPU R4300, decodificação e execução de um subconjunto de instruções.
- Sequenciamento de fluxo de controle, delay slots e parte do tratamento de exceções.
- COP0 básico, estado de entrada e registradores de exceção.
- Sessão de execução em lotes finitos e diagnósticos de CPU.
- Testes unitários correspondentes aos módulos listados em `src/cleanTest`.

### Limitações que permanecem

- O handoff HLE não executa IPL3/PIF; não equivale a um boot completo e validado de jogo.
- O perfil CIC disponível não comprova compatibilidade geral nem garante a inicialização do GoldenEye.
- A CPU R4300 ainda não implementa o conjunto completo de instruções e comportamentos arquiteturais.
- TLB/MMU completa, COP1/FPU e outros aspectos do sistema ainda precisam ser implementados e testados conforme a necessidade do alvo.
- RSP, RDP, VI, apresentação de framebuffer e áudio ainda não estão conectados ao fluxo executável da interface limpa.
- A tela atual permite selecionar ROM, preparar handoff e executar passos/lotes de CPU manualmente. Ela não apresenta um jogo em execução.

## 4. CI: o que foi confirmado

No HEAD acima, o workflow GitHub Actions de ID `42111831579` terminou com estado `completed` e conclusão `success`.

Link: https://github.com/jovemaprendz55-byte/N64-Android/actions/runs/42111831579

Isso confirma o sucesso do workflow para aquele commit. Não confirma execução de ROM comercial, compatibilidade com GoldenEye, renderização gráfica ou áudio no aparelho.

## 5. Sequência de desenvolvimento da versão limpa

### Etapa A — estabilizar fundações
1. Revisar a semântica do estado R4300 e a distinção entre registradores conhecidos e desconhecidos.
2. Padronizar resultados de instrução executada, instrução válida ainda não implementada e codificação reservada.
3. Garantir que instruções não suportadas não produzam mutações parciais ou exceções internas ambíguas.
4. Ampliar testes unitários para instruções, delay slots, faults de memória e exceções.

### Etapa B — memória e exceções
1. Definir regiões físicas e virtuais necessárias ao alvo.
2. Implementar TLB/MMU de forma explícita e testável, sem fingir mapeamentos ausentes.
3. Cobrir refill, invalid, modified, address errors e bus errors com testes de estado antes/depois.
4. Integrar COP0 e fluxo de exceção à CPU.

### Etapa C — runtime HLE independente
1. Definir estruturas e serviços de sistema convidados na arquitetura limpa.
2. Implementar threads, escalonamento e filas de mensagens somente com especificação e evidência verificáveis.
3. Definir chamadas HLE por endereços/símbolos validados para a ROM-alvo.
4. Criar testes de integração para cada serviço antes de conectar o jogo.

### Etapa D — dispositivos e primeiro quadro
1. Implementar interface de barramento e dispositivos com estado observável.
2. Integrar SP/RSP e DMA, com reconhecimento verificável de tarefas.
3. Integrar DP/RDP e os comandos gráficos realmente observados no alvo.
4. Implementar VI e apresentar framebuffer real na tela Android.
5. Integrar áudio e temporização após o caminho gráfico mínimo estar demonstrado.

### Etapa E — validação no aparelho
1. Usar ROM legal do próprio usuário, sem solicitar ou distribuir o arquivo.
2. Registrar cabeçalho, região, CIC, PC, contagem de instruções, exceções e progresso dos dispositivos.
3. Distinguir framebuffer sintético de quadro produzido pelo jogo.
4. Só declarar o primeiro jogo funcional quando houver evidência no aparelho de um quadro real do jogo.

## 6. Regras para registros futuros

Cada atualização deve indicar:
- data e branch;
- commit/HEAD exato;
- arquivos alterados;
- testes executados e resultado;
- evidência observada;
- limitações ainda abertas;
- próximo passo pequeno e verificável.

Usar os rótulos **Confirmado no código**, **Confirmado por teste**, **Observado no aparelho**, **Hipótese** e **Ainda não testado**. Não transformar hipótese em fato.

## 7. Próxima tarefa recomendada

Antes de ampliar a CPU indiscriminadamente, auditar os módulos ativos de estado da CPU, decoder, stepper, executores e sessão. Produzir uma matriz de instruções suportadas / válidas não suportadas / reservadas e corrigir as lacunas que afetam o caminho de boot. Em seguida, atualizar este documento com resultados e testes reais.

---

Este documento descreve somente a arquitetura limpa e passa a ser a referência de estado e roteiro para sua continuidade.