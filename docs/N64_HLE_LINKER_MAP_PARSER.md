# Novo parser de linker map HLE — 2026-11-04

Branch: `dev/n64-hle-clean-reboot`.

Foi adicionado `N64HleLinkerMapParser` para importar somente linhas estritas de símbolo no formato GNU ld (endereço hexadecimal seguido de nome). Cada símbolo recebe evidência com o identificador de origem e número da linha. A validação final de segmento/alinhamento continua centralizada em `N64HleVerifiedSymbolTable`.

Segurança contra dados parciais:
- O parser valida todas as linhas candidatas, segmentos, nomes e endereços duplicados antes de inserir qualquer símbolo na tabela.
- Linhas de seção, comandos do linker e sintaxes não reconhecidas são ignoradas, não interpretadas como símbolos.
- O chamador precisa identificar a revisão/build que gerou o mapa; o parser não consegue provar sozinho que o mapa pertence à ROM carregada.
- Não há mapa final verificado de GoldenEye USA no repositório. Portanto, esta etapa não registra endereços GoldenEye nem cria call gates reais.

Arquivos:
- `app/src/clean/java/com/jsr/n64android/N64HleLinkerMapParser.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64HleLinkerMapParserTest.kt`

Testes cobrem linhas válidas, linhas ignoradas, evidência por linha, endereços inválidos, duplicação e ausência de mutação parcial.

Limite: parser e tabela de símbolos ainda não conectam chamadas guest à implementação de libultra. Próxima etapa é obter/validar um mapa de símbolos da build USA exata ou outra fonte reproduzível; sem isso, os endereços permanecem desconhecidos. O boot HLE, CPU, RSP/RDP e integração com a Activity continuam por implementar.
