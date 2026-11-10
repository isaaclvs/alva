# Inventário de paridade — PWA atual → Android offline

Data: 10/11/2026. Status: **proposto**. Nada aqui foi implementado; serve para
não perder comportamento na migração e para dar critério às tasks do GitHub
([#33](https://github.com/isaaclvs/alva/issues/33)). Decisão em
[ADR 0001](../adr/0001-android-offline-com-rails.md); regras de sync em
[android-sync-contract-v1.md](android-sync-contract-v1.md).

Baseline em uso hoje: PWA instalável (Rails + Turbo/ActionCable, sem offline) e
APK TWA com widget de inclusão rápida e atalhos do launcher. Os dois continuam
funcionando durante a migração; a troca da entrada padrão do APK fica para a
etapa de transição.

## Paridade funcional

| Funcionalidade | Hoje (PWA + APK conectado) | Destino Android offline | Sync implicado | Task |
| --- | --- | --- | --- | --- |
| Adicionar item | Campo aceita vírgula/ponto e vírgula/linha, máx. 30, dedup por nome normalizado | Mesmo campo, gravando local primeiro | `add_items` idempotente | #43 |
| Dica de duplicado ao digitar | `duplicate_hint_controller.js` compara com pendentes (sem acento/caixa) | Mesma dica sobre o banco local (`DuplicateHint`, módulo pronto; a tela é da #43) | Só dica; servidor decide | #42 |
| Acabou de adicionar | Itens criados/revividos com seletor de categoria (`from=recent`) | Mesmo retorno, só no aparelho que adicionou | Resposta HTTP, nunca broadcast | #43 |
| Categorização automática | `ItemCategorizer` sobre `CatalogItem`, fallback "Outros" | Módulo Kotlin equivalente (`data/matching`), corpus compartilhado, dicionário no Room | Dicionário sincronizado | #42 |
| Corrigir nome/categoria | Lápis troca a linha; categoria à mão ensina o dicionário | Mesmo formulário local, rascunho preservado | `correct_item` + `learn` | #47, #48 |
| Aprender categoria | `CatalogItem` `learned: true`, `db:seed` não sobrescreve | Grava localmente e envia aprendizado | Precondição de revisão do dicionário | #42, #47 |
| Marcar/desmarcar | Checkbox grava `status` + `purchased_at`; vira checklist no modo compras | Estado local imediato | `set_item_state` (estado, nunca toggle) | #39, #43 |
| Desfazer marcação | `PurchaseUndo` (token 1 min) só no aparelho; toast ~5 s | Compensação local e comando offline ordenado | `undo_item_state`, sem token web | #49, #50 |
| Remover item | Formulário "Remover" ou swipe para a esquerda (só toque) | Mesmos dois caminhos, removendo local | `remove_item` | #50 |
| Desfazer remoção | `ListItemSnapshot` assinado (1 min) reinsere com atributos originais | Snapshot local + comando compensatório | `restore_item` | #49, #50 |
| Categorias fixas com contagem | Seções, "x/y" e "2 restantes"/"tudo pego" em compras | Igual, calculado no banco local | Só dados, sem comando | #43 |
| Habituais | Criar/remover; adicionar selecionados ou todos; unicidade por nome | Coleção local, sem limite de 30 | `create/remove_habitual`, `add_habituals` | #53 |
| Comprar de novo | Até 5 atalhos do histórico (frequência + recência) | Ranking no histórico local | Snapshot traz arquivados | #54 |
| Atalhos / inclusão rápida | Widget "quick add" envia direto ao Rails com token de inclusão; atalhos do launcher | Widget grava no banco local e entra na fila | Mesmo módulo de lista, fila offline | #56 |
| Busca e "Só pendentes" | Filtros client-side sempre visíveis; lupa foca sem limpar | Igual, sobre lista local | Nenhum | #55 |
| Iniciar/sair das compras | Preferência por aparelho (cookie 24 h), CSS, wake lock, vibração; servidor renderiza o estado | Preferência no DataStore, mesma ausência de flash | Nunca transmitir preferência | #55 |
| Tema e cor | Cookies por aparelho, tokens semânticos no servidor | Preferência no DataStore, mesmas cores | Nunca transmitir preferência | #55 |
| Tela aberta (Adicionar/Lista) | Cookie `screen`, troca no cliente sem request | Preferência no DataStore | Nunca transmitir preferência | #55 |
| Autofocus no adicionar | Só quando abre em Adicionar e não está em compras | Igual | Nenhum | #43 |
| Compra feita | `ShoppingTrip` arquiva os comprados via `update_all`; pendentes ficam | Finalização local com os itens vistos | `finish_shopping` com IDs/versões | #51, #52 |
| Desfazer compra | `DELETE /shopping_trips/:id`, só a viagem mais recente | Compensação local + comando | `undo_shopping` com precondição | #51, #52 |
| Exportar PDF | `GET /lista.pdf`; botão no menu ⋯ da Lista | Preservar exportação/compartilhamento | Dados locais; task própria | #57 |
| Tempo real | Turbo Streams + ActionCable re-renderizam HTML | Sinal de invalidação dispara sync (dado, não HTML) | Revisão + snapshot | #46 |
| Sair/senha da casa | `HouseholdAccess`, cookie por aparelho | Pareamento uma vez com a senha da casa | Credencial de sync revogável | #44 |

## O que fica no aparelho (nunca no broadcast)

- Tema, cor, tela aberta e modo compras.
- Retorno de adicionar (`#add_feedback`) e realce de "acabou de adicionar".
- O toast de Desfazer, restrito a quem agiu.

## Autoridade e recuperação

- O servidor é a autoridade de duplicados, ordem e categorização final; resolve
  divergências sem perder o nome digitado.
- Categoria escolhida à mão offline vale localmente e gera aprendizado ao
  sincronizar; sem escolha manual, a categoria automática pode ser reconciliada
  pelo dicionário atual do servidor.
- Falha permanente ou conflito exige aviso persistente com recuperação; o fluxo
  normal de adicionar não abre modal.
- Cópia local funciona sem rede; compartilhar entre aparelhos exige servidor
  alcançável. Operação que nunca saiu do aparelho não é recuperável do servidor
  após desinstalação — política de backup local precisa ser documentada e
  testada (#58).

## Lacunas conhecidas a fechar antes da transição

- [ ] Nenhum item de paridade pode desaparecer sem task; qualquer novo achado
      entra nesta tabela e na issue #33.
- [ ] PDF precisa de caminho offline ou de decisão explícita de mantê-lo
      dependente do servidor (hoje é o único recurso que só existe no servidor).
- [ ] Meta inicial medida em aparelhos reais no release: retorno local ao
      adicionar/marcar em até 100 ms e lista utilizável em até 1 s numa abertura
      fria, sem aguardar rede (#59).
