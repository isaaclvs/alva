# Histórico de compras (somente leitura)

Implementado na **#159**. Decisão do dono em 18/11/2026: o item saiu de fora de
escopo e entrou na PWA e no app nativo.

## O que já existia

Cada "Compra feita" cria uma `ShoppingTrip` e arquiva nela os itens comprados
(`Household#finish_shopping!`). Os dados sempre existiram: `ShoppingTrip
has_many :list_items`, e `ListItem.on_list` / `ListItem.archived` já separam a
lista viva do histórico. Só faltava a visualização.

## A leitura (`ShoppingHistory`)

`app/services/shopping_history.rb` é a única leitura do histórico. Ela serve a
PWA e a API, então as duas mostram a mesma ordem e o mesmo conteúdo.

- **Uma página por mês.** O mês de uma viagem é o mês do `finished_at` no
  relógio da casa (America/Sao_Paulo), não em UTC: uma compra feita às 22:30 do
  dia 31 pertence àquela noite, mesmo que em UTC já seja dia 1. Por isso o
  agrupamento acontece em Ruby (`in_time_zone`), não em SQL (`strftime` agruparia
  em UTC).
- `Page` traz o `month` pedido (ou o mais recente com viagem), `months` (todos os
  meses com viagem, mais recentes primeiro, com a contagem de viagens) e as
  `trips` daquele mês, mais recentes primeiro. Um `month` sem viagem ou mal
  formado cai no mais recente, em vez de virar erro.
- `Trip` traz a viagem, `items_count` e os itens agrupados por categoria na mesma
  ordem da lista viva (`Household#list_items_by_category`): posição da categoria,
  itens sem categoria no fim, nome sem acento/caixa. `names` é o que
  "Adicionar tudo de novo" re-adiciona, na ordem da tela.

## PWA

- `GET /compras` (`ShoppingHistoryController#index`) renderiza a tela, com o link
  "Compras" no menu ⋯ das duas telas.
- Cada viagem é um `<details>` fechado com a data ("Quinta, 12 de novembro ·
  18:30") e o tamanho ("3 itens"). Aberto, mostra as categorias com o mesmo
  ícone da lista, os itens e o botão **Adicionar tudo de novo**.
- `POST /compras/:id/adicionar` pega os nomes da viagem e chama
  `Household#add_item_names` — o mesmo caminho do campo de adicionar e do painel
  de habituais, então duplicados, itens comprados que voltam e o broadcast único
  se comportam igual. O resumo (`ListItemsHelper#add_items_notice`) só aparece
  para o aparelho que agiu, em `#history_feedback`; a lista chega aos outros pelo
  broadcast.
- A tela é somente leitura: nada no histórico é editável, e não há gráficos.
- Alvos de toque da tela nova têm pelo menos 44 CSS px (cabeçalho, navegação de
  meses, resumo de cada viagem e o botão de repetir).

## API (`GET /api/v1/history`)

Aditiva na v1: um cliente antigo nunca a chama, e ela não grava nada (a revisão
da casa não se move). Credencial `sync` no `Authorization`, como o snapshot; a
casa vem só da credencial. `?month=YYYY-MM` escolhe a página; sem o parâmetro, ou
com um mês sem viagem, vem a mais recente.

```json
{
  "month": "2026-11",
  "months": [ { "key": "2026-11", "trip_count": 2 } ],
  "older_month": "2026-10",
  "newer_month": null,
  "trips": [
    { "public_id": "…", "finished_at": "2026-11-12T21:30:00.000000Z", "items_count": 2,
      "items": [ { "public_id": "…", "name": "Banana", "category_public_id": "…" } ] }
  ]
}
```

- `items` já vem na ordem de exibição (posição da categoria, depois nome sem
  acento). `category_public_id` é `null` para item sem categoria.
- `items_count` é o tamanho da lista; um corpo em que ele não bate com `items` é
  rejeitado pelo cliente (não é o documento do servidor).
- Sem histórico: `month` e os dois vizinhos `null`, `months` e `trips` vazios.
- Erros: `401 unauthorized` e `403 forbidden_scope`, como o snapshot; sem sufixo
  de formato e só `GET`.

## Android

- `HistoryTransport` / `HttpHistoryTransport` leem a página; `HistoryParser` é
  estrito (corpo que não é o documento é rejeitado inteiro) e ignora campos
  extras, como o resto da v1.
- `OnlineRepository.history(month)` é separado de `state`: falha de histórico
  nunca tira a lista da tela.
- `OnlineRepository.readdHistory(trip)` repete a compra com o comando
  `add_items` que já existia, em lotes de `AddItems.MAX_NAMES` (30). Não há
  comando novo, então o APK 2.0.4 continua funcionando contra o mesmo servidor.
- `HistoryScreen` (menu ⋯ → "Compras") usa o tema, os ícones de categoria e o
  resumo de adicionar da PWA. O resumo de "Adicionar tudo de novo" aparece na
  linha de feedback do Adicionar, como o de um add feito pelo campo.
- As datas usam o relógio do aparelho (`HistoryDate`), que é o da casa.

## Testes

- Rails: `test/models/shopping_history_test.rb`,
  `test/controllers/shopping_history_controller_test.rb`,
  `test/integration/sync_history_test.rb` e
  `test/system/shopping_history_test.rb`.
- Android: `HistoryParserTest`, `HistoryActionsTest`,
  `HttpHistoryTransportTest`, `HistoryDisplayTest` (JVM) e
  `HistoryScreenTest` (instrumentado).
