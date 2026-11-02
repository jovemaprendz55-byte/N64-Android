# Expansão HLE e TLB — 2026-11-02

## Implementado nesta rodada

### R4300 TLB

Arquivo: `app/src/main/java/com/jsr/n64android/R4300Tlb.kt`.

- Snapshot imutável de todos os slots, preservando entradas vazias.
- Restauração de snapshot com validação completa antes de alterar a TLB ativa.
- Validação de tamanho do snapshot e normalização/validação de ASID, PageMask e alinhamento de cada entrada.
- Tradução detalhada opcional com endereço físico, índice da entrada, tamanho da página, página par/ímpar, ASID armazenado e estado Global.
- O método antigo `translate()` continua disponível e retorna o mesmo endereço físico por meio da nova tradução detalhada.
- Continua aceitando PageMask já suportados, páginas pareadas, PFN e regras ASID/G.

Limites: não é uma MMU cycle-accurate; não implementa cache/TLB timing, exceções completas em todos os cenários ou efeitos de microarquitetura. O snapshot da TLB não é ainda um snapshot completo do estado da CPU/COP0.

### HLE de sistema

Arquivo: `N64HleOsKernel.kt`.

- Filas agora têm espera real de threads no modelo host-side.
- `sendMessageBlocking`: se a fila estiver cheia, estaciona a thread atual e guarda a mensagem pendente.
- Quando um receiver remove uma mensagem, a mensagem do sender bloqueado mais antigo é inserida na vaga e a thread é acordada.
- `receiveMessageBlocking`: se a fila estiver vazia, estaciona a thread atual.
- Quando chega uma mensagem, um receiver bloqueado é acordado.
- Operações não bloqueantes não ultrapassam um sender mais antigo já bloqueado.
- Snapshots das filas mostram contagem de senders/receivers realmente aguardando.

Semântica da API host-side: nas operações bloqueantes, retorno `false` (send) ou `null` (receive) significa que a thread chamadora foi bloqueada; o scheduler deve despachá-la novamente após o wakeup. A mensagem pendente do sender é entregue à fila ao liberar espaço.

## Testes adicionados

- Snapshot/restore preserva slots vazios, entradas e tradução.
- Snapshot de tamanho incorreto ou com entrada inválida é rejeitado sem mutação parcial.
- Tradução detalhada informa slot, página, tamanho, ASID e Global.
- Receiver bloqueado acorda quando chega mensagem.
- Sender bloqueado acorda e sua mensagem entra na fila ao liberar espaço.
- Sender não bloqueante não ultrapassa sender bloqueado mais antigo.

## Ainda não implementado

- ABI guest real de libultra (OSThread/OSMesgQueue em RDRAM).
- Snapshot/restauração completo do R4300 (GPR, FPR/FCR, HI/LO, COP0, PC, delay slot e demais estados).
- Context switch real da CPU.
- TLB exception/refill completo e integração de todos os eventos de hardware.
- PIF/Joybus HLE completo, suporte a todos os CICs, RSP/RDP e áudio completos.
- Integração do kernel host-side com chamadas da ROM.

Nenhuma destas alterações prova compatibilidade com ROM real nem execução no Poco C85. A validação depende do CI Android e, posteriormente, teste no dispositivo.
