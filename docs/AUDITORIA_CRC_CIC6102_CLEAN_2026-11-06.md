# Auditoria crítica do CRC1/CRC2 CIC 6102 — versão limpa

Data: 2026-11-06  
Branch: `dev/n64-hle-clean-reboot`  
Escopo: inspeção estática; nenhum código de produção/teste alterado nesta etapa.

## Arquivos examinados

- `app/src/clean/java/com/jsr/n64android/N64HleBootProfileFactory.kt`
- `app/src/clean/java/com/jsr/n64android/N64HleCartridgeImage.kt`
- `app/src/cleanTest/java/com/jsr/n64android/N64HleBootProfileFactoryTest.kt`
- `docs/CONTINUIDADE_CHAT.md`

## Achado crítico

A implementação `calculate6102Checksum()` apresenta uma divergência pontual em relação à fórmula documentada para CRC1/CRC2 de ROM N64.

Na referência técnica `n64dev.org/n64crc.html`, corroborada pelas notas independentes do projeto CC0 `Dragorn421/n64checksum`, a atualização do acumulador equivalente a `t2` é:

- se `t2 > d`: `t2 = t2 XOR rotateLeft(d, d AND 31)`
- caso contrário (incluindo igualdade): `t2 = t2 XOR t6 XOR d`

No código limpo atual, a variável equivalente `a2` usa `if (a2 < word)` para selecionar `a3 XOR word`; no caso contrário seleciona `rotate`.

Como `a3` já recebeu `a3 + word`, a expressão `a3 XOR word` corresponde ao ramo `t6 XOR d`. Para valores diferentes, os ramos coincidem com a referência: quando `a2 > word`, o código usa rotate; quando `a2 < word`, usa `a3 XOR word`. A divergência ocorre somente quando `a2 == word`: a referência usa `a3 XOR word`, mas o código atual usa rotate. Portanto, não é correto dizer que a condição está globalmente invertida; é um erro no caso de igualdade.

## Impacto provável

- CRC1 usa `a3 XOR t2(carry) XOR t3`; essa parte aparenta corresponder à fórmula.
- CRC2 usa `s0 XOR a2 XOR t4`; o acumulador pode divergir quando uma palavra processada é exatamente igual ao valor atual de `a2`.
- O teste `N64HleBootProfileFactoryTest.checksum6102()` repete a mesma lógica de produção, inclusive o caso de igualdade. Por isso, os testes sintéticos não detectam essa divergência.
- O impacto em ROMs comerciais depende de ocorrer essa igualdade durante o processamento. Não há evidência de que isso ocorra na ROM GoldenEye; não afirmar que GoldenEye é rejeitado por esse motivo.

## Evidência técnica externa

1. `n64dev.org/n64crc.html`: implementação de referência descreve os seis acumuladores e a condição `t2 > d`.
   https://n64dev.org/n64crc.html
2. `Dragorn421/n64checksum` (CC0-1.0), README, seção sobre seed e CRC da imagem de 1 MiB. Explica que o checksum fica no cabeçalho em 0x10 e cobre 1 MiB a partir de 0x1000; também referencia a implementação n64dev.
   https://github.com/Dragorn421/n64checksum/blob/main/README.md
3. A mesma documentação diferencia o hash/checksum de IPL3 (offsets 0x40..0xFFF) do CRC1/CRC2 da imagem de 1 MiB. Não confundir os dois algoritmos.

## Próxima correção proposta (ainda não aplicada)

1. Criar testes independentes de produção para a fórmula 6102, com vetores conhecidos/fixtures calculados por implementação de referência independente ou checksum oficial de ROM legal.
2. Corrigir a condição para refletir exatamente `t2 > d`, sem simplesmente copiar o helper de teste.
3. Adicionar caso de teste que falha com a implementação atual e passa com a fórmula correta.
4. Verificar CRC1 e CRC2 de uma ROM GoldenEye NTSC-U legítima no dispositivo do usuário, sem solicitar ou distribuir a ROM.
5. Só então manter/ajustar o perfil HLE e documentar resultado de validação.

## Estado desta etapa

- Auditoria estática concluída.
- Nenhuma alteração de produção ou testes.
- Nenhum Gradle/CI executado nesta etapa.
- Nenhuma ROM comercial foi carregada ou compartilhada.
- GoldenEye não foi executado.
- A divergência pontual está demonstrada por comparação de código com a fórmula de referência. Sua ocorrência/impacto em ROMs reais ainda precisa ser medido com vetores externos.

Documento exclusivo da versão limpa.


## Correção aplicada e vetores independentes — 2026-11-06

A comparação direta com as implementações de referência confirmou o detalhe do caso de igualdade. A regra é:

`if (t2 > d) t2 ^= rotateLeft(d, d & 31); else t2 ^= t6 ^ d;`

O código anterior usava `if (a2 < word) (a3 xor word) else rotate`. Isso coincide com os dois ramos quando os valores são diferentes, mas escolhe `rotate` quando `a2 == word`, enquanto a referência escolhe `a3 xor word`.

### Alterações publicadas

- Produção: `N64HleBootProfileFactory.calculate6102Checksum()` agora usa `if (a2 > word) rotate else (a3 xor word)`.
- Teste: removido o helper de checksum que copiava a lógica de produção.
- Teste: substituído o checksum do fixture sintético por valores fixos calculados independentemente.
- Teste novo: primeiro word do bloco de 1 MiB é exatamente o seed inicial `0xF8CA4DDC`, exercitando `t2 == d`.

### Vetores fixos usados

| Fixture | CRC1 | CRC2 |
|---|---:|---:|
| Padrão determinístico `byte(i * 13 + 7)` | `0xF6CC53D6` | `0x5198A84B` |
| Primeira palavra igual ao seed; restante zero | `0x095ED665` | `0x12367389` |

Os valores foram calculados com uma tradução independente da implementação de referência n64dev.org (não com o helper Kotlin removido). Para o vetor de igualdade, a implementação antiga produzia CRC2 `0xD4E40130`, diferente do valor de referência `0x12367389`; a implementação corrigida produz o par esperado.

### Referências externas

- n64dev.org, algoritmo C de referência: https://n64dev.org/n64crc.html
- Reimplementação independente em C: https://github.com/z64dev/z64decompress/blob/main/src/n64crc.c
- Notas de pesquisa sobre checksums N64: https://github.com/Dragorn421/n64checksum

### Validação pendente

As alterações de código e teste foram enviadas à branch limpa. A aprovação de CI ainda deve ser consultada após o push. Estes vetores são fixtures sintéticas; não substituem a verificação da ROM GoldenEye legítima do usuário e não demonstram execução do jogo.
