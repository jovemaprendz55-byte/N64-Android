# N64-Android

Emulador Nintendo 64 para Android, com interface própria e foco em desempenho em aparelhos ARM64.

**Objetivo principal:** executar jogos de Nintendo 64, começando pelos testes de compatibilidade e avançando até GoldenEye 007 no Poco C85.

## Estratégia técnica

Este projeto não vai reimplementar o Nintendo 64 do zero. Vamos estudar a arquitetura do Mupen64Plus e reutilizar/adaptar componentes cuja licença permita, mantendo avisos de copyright, textos de licença e histórico das modificações.

- **Base de emulação:** Mupen64Plus Core (GPL-2.0).
- **Frontend Android:** Kotlin/Java e JNI, desenvolvido neste projeto.
- **Vídeo:** avaliar plugins compatíveis com Android e GLES.
- **Áudio:** backend Android de baixa latência.
- **Entrada:** controles touch e controles físicos com implementação própria.
- **Testes:** automatizados no CI e testes reais no aparelho quando houver uma versão instalável.

## Engenharia reversa / análise

Aqui, “engenharia reversa” significa estudar o código-fonte disponível, mapear fluxo de inicialização, ABI dos plugins, ciclo de vida da ROM, renderização, áudio e entrada, e documentar como cada parte será integrada. Não significa remover autoria ou licenças.

## Licenças

O Mupen64Plus Core declara GNU GPL versão 2. Cada componente de terceiros será verificado individualmente antes de ser incorporado. Componentes GPL-3.0 ou incompatíveis com a estratégia de distribuição não serão copiados para este projeto sem uma decisão explícita sobre a licença final.

Consulte [docs/LICENSING.md](docs/LICENSING.md) antes de adicionar código externo.

## Estado atual

- [x] Repositório novo criado.
- [x] Estratégia inicial documentada.
- [x] Fixar a versão/commit do Mupen64Plus Core para análise.
- [ ] Auditar dependências e licenças dos plugins.
- [ ] Criar esqueleto Android + NDK + JNI (próxima etapa).
- [ ] Integrar e validar inicialização do Core.
- [ ] Integrar vídeo, áudio e entrada.
- [ ] Executar testes no Poco C85.

## Documentação técnica

- [Análise inicial do Core](docs/CORE-ANALYSIS.md)
- [API pública e plano de integração Android](docs/API-PORTING.md)
- [Auditoria de licenças](docs/LICENSING.md)

## Referências

- Mupen64Plus Core: https://github.com/mupen64plus/mupen64plus-core
- Mupen64Plus AE: https://github.com/mupen64plus/mupen64plus-ae
