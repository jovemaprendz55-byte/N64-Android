# Auditoria de validação de encodings SPECIAL — R4300 limpo

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: decoder, classificador de exceções, executor SPECIAL e testes ativos da arquitetura limpa.

## 1. Resumo

O decoder separa corretamente a extração dos campos da execução. Porém, o reconhecimento de um `functionCode` SPECIAL não valida, por si só, as restrições dos outros campos da instrução. Algumas operações R4300 exigem campos específicos em zero ou possuem regras de codificação próprias.

Sem uma etapa de validação estrutural, uma palavra com function code conhecido pode alcançar o executor SPECIAL mesmo que seus campos auxiliares não correspondam a uma codificação legal.

Este é um achado estático. Não foi executado teste Gradle nem teste no dispositivo nesta auditoria.

## 2. Comportamento observado

### Decoder

Arquivo: `N64HleR4300InstructionDecoder.kt`.

O decoder extrai opcode, rs, rt, rd, shiftAmount, functionCode e imediatos. Para opcode SPECIAL, resolve `specialOperation` somente pelo function code.

Isso é adequado para decodificação de campos, mas não é validação completa do encoding.

### Classificador

Arquivo: `N64HleR4300ExceptionInstructionClassifier.kt`.

O classificador trata:
- function code SPECIAL desconhecido como Reserved Instruction;
- SYSCALL e BREAK como exceções específicas;
- traps condicionais com avaliação de registradores;
- REGIMM inválido e alguns encodings COP0 reservados.

Não há, no caminho inspecionado, validação geral das restrições de rs/rt/rd/sa para cada operação SPECIAL conhecida.

### Executor SPECIAL

Arquivo: `N64HleR4300SpecialExecutor.kt`.

O executor seleciona a operação pelo enum e usa os campos relevantes para calcular resultado. Não valida de forma geral todos os campos que deveriam ser zero ou os formatos específicos por operação antes de ler/alterar estado.

## 3. Risco técnico

Um encoding SPECIAL com function code conhecido, mas campos inválidos, pode ser executado como se fosse legal. O problema não é apenas falta de suporte: é risco de aceitar uma instrução arquiteturalmente reservada.

Exemplos de famílias que exigem regras específicas:
- deslocamentos imediatos: campos não usados devem respeitar as restrições da operação;
- JR/JALR: campos de destino/shift e operandos reservados precisam obedecer ao formato;
- MFHI/MFLO e MTHI/MTLO: campos não utilizados têm restrições;
- operações aritméticas/logicais SPECIAL: shift amount e campos auxiliares devem ser validados;
- SYNC: possui formato próprio e ainda não tem execução implementada;
- traps SPECIAL: os campos não utilizados também precisam ser avaliados conforme a codificação.

A lista exata de máscaras/restrições deve ser derivada da especificação VR4300/R4300 e documentada por operação. Não inferir que todo campo não usado é zero sem confirmar a regra da instrução.

## 4. Contrato recomendado

Adicionar uma etapa pura de validação estrutural entre decode e classificação/execução, por exemplo:

- `LegalEncoding(operation)`
- `ReservedEncoding(reason)`
- `UnknownOperation(functionCode)`

A ordem conceitual deve ser:

1. Decodificar campos brutos.
2. Validar a legalidade estrutural do encoding.
3. Classificar exceções arquiteturais conhecidas (SYSCALL, BREAK, traps e RI).
4. Verificar capacidade de execução.
5. Executar somente se encoding for legal e operação suportada.
6. Avançar PC/concluir delay slot somente depois de resultado de execução bem-sucedida.

A validação precisa ser pura: não pode ler registradores nem mutar CPU.

## 5. Testes necessários

Para cada operação SPECIAL implementada:
- um encoding válido de referência;
- variações de cada campo restrito;
- garantir que encoding reservado entra em RI;
- garantir que PC, GPRs, HI/LO e flags de conhecimento não mudam antes da entrada arquitetural da exceção;
- testar a mesma instrução em delay slot e verificar BD/EPC e cancelamento da pendência.

Para operações válidas mas ainda não implementadas:
- garantir que a validação estrutural aceite o encoding legal;
- garantir que a capacidade ausente não seja confundida com RI.

## 6. Relação com o problema de despacho

A validação estrutural não substitui a correção do fallback no stepper. São problemas independentes:

- encoding inválido precisa resultar em exceção arquitetural RI;
- encoding válido mas não implementado precisa produzir resultado explícito de capacidade ausente;
- encoding válido e suportado pode ser executado;
- nenhuma dessas situações deve cair silenciosamente no executor imediato.

## 7. Status

- **Inspeção estática:** decoder, classificador, executor SPECIAL e testes-fonte.
- **Não executado:** Gradle, testes unitários ou aparelho.
- **Não alterado:** código de produção e testes.
- **Pendente:** matriz normativa de campos por operação e implementação da validação estrutural.

## 8. Próxima etapa

Construir a tabela normativa de campos para cada operação SPECIAL reconhecida e implementada, usando referência técnica VR4300/R4300. Depois, escrever testes parametrizados para os campos restritos antes de implementar o validador.

---
Auditoria exclusiva da versão limpa. Não usar código da versão antiga.
