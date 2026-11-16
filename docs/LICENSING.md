# Auditoria de licenças e código de terceiros

## Regra do projeto

Antes de copiar, adaptar, compilar ou distribuir qualquer componente, registrar:
- repositório e URL de origem;
- commit/tag exato utilizado;
- licença indicada pelo próprio componente e seus arquivos de licença;
- arquivos incorporados e alterações feitas;
- avisos de copyright e obrigações de redistribuição.

Não presumir que todos os componentes de um emulador possuem a mesma licença.

## Mupen64Plus Core

Repositório: https://github.com/mupen64plus/mupen64plus-core

O arquivo `LICENSES` do upstream declara que o Mupen64Plus-Core é licenciado sob GNU General Public License versão 2. O mesmo arquivo identifica componentes auxiliares sob outras licenças (incluindo BSD, zlib/libpng, LGPL e Bitstream). Portanto, a auditoria deve ser feita por componente/arquivo, e não apenas pelo nome do projeto.

Plano:
1. Fixar um commit específico do Core.
2. Trazer o código como submódulo ou cópia versionada com proveniência clara.
3. Preservar os arquivos de licença e avisos originais.
4. Documentar cada patch local.
5. Verificar a licença de cada plugin separadamente.

## Mupen64Plus AE e outros frontends

O código do frontend Android não deve ser presumido como GPL-2.0 apenas por usar o Mupen64Plus. Verificar a licença do repositório e dos submódulos antes de reutilizar qualquer arquivo. Podemos estudar arquitetura e comportamento como referência sem copiar código incompatível.

## Política de contribuição

- Não remover cabeçalhos de copyright.
- Não rebatizar código de terceiros como código original.
- Não misturar código GPL-3.0 em uma distribuição declarada GPL-2.0.
- Manter uma lista de componentes e licenças atualizada.
- Em caso de dúvida sobre compatibilidade, isolar o componente e não incorporá-lo até esclarecer a licença.

Este documento é um registro técnico do projeto, não aconselhamento jurídico.


## Mupen64Plus RSP CXD4 (LLE)

Repositório: https://github.com/mupen64plus/mupen64plus-rsp-cxd4

A integração Vulkan seleciona o interpretador RSP CXD4 para executar o
microcódigo gráfico no próprio RSP e entregar a lista de comandos ao callback
ProcessRDPList. A revisão fixada para Android ARM64 é
`b8e747be772b8db017bb981bff188324b919162e`. Os arquivos-fonte do plugin
contêm avisos GNU GPL versão 2 ou posterior; preservar seus cabeçalhos e
arquivos de licença junto à distribuição.

O plugin é compilado como biblioteca compartilhada ARM64 e carregado pelo
Core Mupen64Plus. Antes de distribuir o APK, manter o código-fonte
correspondente e os scripts de build acessíveis, conforme as obrigações da
GPL-2.0-or-later. Verificar novamente os avisos e licenças dos arquivos
individuais antes de qualquer redistribuição.

O RSP-HLE deixa de ser o plugin selecionado nesta branch; não remover seus
avisos ou fontes de versões anteriores do projeto sem uma auditoria separada.
