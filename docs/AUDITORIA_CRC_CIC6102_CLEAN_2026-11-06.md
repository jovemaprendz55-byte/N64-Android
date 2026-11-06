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

A implementação `calculate6102Checksum()` parece divergir da fórmula documentada para CRC1/CRC2 de ROM N64.

Na referência técnica `n64dev.org/n64crc.html`, corroborada pelas notas independentes do projeto CC0 `Dragorn421/n64checksum`, a atualização do acumulador equivalente a `t2` é:

- se `t2 > d`: `t2 = t2 XOR rotateLeft(d, d AND 31)`
- caso contrário: `t2 = t2 XOR t6 XOR d`

No código limpo atual, a variável equivalente `a2` usa o teste `a2 < word` e seleciona os dois ramos ao contrário:

- se `a2 < word`: `a2 XOR (a3 XOR word)`
- caso contrário: `a2 XOR rotate`

Como `a3` já recebeu `a3 + word`, a expressão `a3 XOR word` corresponde ao ramo `t6 XOR d`; portanto, a divergência é a condição de comparação invertida, não apenas uma diferença de nomes.

## Impacto provável

- CRC1 usa `a3 XOR t2(carry) XOR t3`; essa parte aparenta corresponder à fórmula.
- CRC2 usa `s0 XOR a2 XOR t4`; como `a2` pode divergir a cada palavra, CRC2 calculado pode ficar incorreto.
- O teste `N64HleBootProfileFactoryTest.checksum6102()` repete a mesma lógica de produção, inclusive a condição suspeita. Por isso, os testes sintéticos podem passar mesmo com a fórmula divergente.
- O perfil HLE pode rejeitar uma ROM comercial válida por divergência de CRC2. Ainda não foi testada uma ROM GoldenEye real; não afirmar que esse impacto foi reproduzido no jogo.

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
- A hipótese de divergência é suficientemente forte para bloquear a confiança no factory até correção e teste independente, mas ainda deve ser validada com vetor externo.

Documento exclusivo da versão limpa.
