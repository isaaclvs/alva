# AGENTS.md — Alva

Contexto para qualquer agente de código trabalhando neste repositório.

## O que é o projeto

App Android de lista de compras compartilhada, para uso doméstico (2 usuários).
Foco absoluto: adicionar um item que acabou em casa precisa ter **fricção zero**.
Quando chega o dia de ir ao mercado, a lista já está organizada por categoria.

Rastreamento de trabalho: o **Linear** (workspace **Alva**, time **ALV**,
https://linear.app/alva-isaac/team/ALV/all) fica como **histórico** do MVP/PWA;
referências SHO/ALV neste documento são decisões daquele período. A **migração
para o app Android nativo offline** é acompanhada no GitHub pela issue
[Alva nativo offline — #33](https://github.com/isaaclvs/alva/issues/33) e suas
tasks; não duplicar a mesma task nos dois lugares. Antes de implementar,
identifique se a mudança é no produto atual (PWA/APK conectado) ou na migração,
e leia o documento correspondente.

## Produto atual e destino nativo

O que está **em uso hoje** e não pode quebrar durante a migração: a PWA
instalável e o APK TWA com widget conectados ao Rails, sem modo offline.

O **próximo destino** (decisão do usuário em 11/11/2026): app Android nativo
em Kotlin/Compose **online**, com paridade com a PWA (mesmas funcionalidades,
temas, cores e fontes). O servidor é a fonte da verdade, sem fila local nem
offline; o cliente usa a API v1 existente. Acompanhamento na #92, plano em
`docs/plans/android-online-parity.md`. Ao final, a release nativa substitui o
Alva 1.3 (TWA) instalando por cima, com o mesmo identificador e a mesma chave.

O **offline está pausado** (#33 e filhas, rótulo `pausado`): app nativo com
Room e fila local, sincronizando com o Rails/SQLite. Kotlin e o desenho cliente
novo/servidor existente foram aprovados em 10/11/2026; a sincronização da #45
já está integrada na variante debug. O código fica no repositório, desligado da
interface, e não deve ser ampliado sem o usuário retomar o offline. Os
documentos abaixo continuam válidos para quando ele voltar.

- Plano da paridade online: `docs/plans/android-online-parity.md`
- Cliente online no Android (`OnlineRepository`, ações, como estender): `docs/plans/android-online-client.md`

- Decisão e consequências: `docs/adr/0001-android-offline-com-rails.md`
- Plano e regras propostas: `docs/plans/android-offline.md`
- Contrato de sincronização v1: `docs/plans/android-sync-contract-v1.md`
- Inventário de paridade: `docs/plans/android-parity-inventory.md`
- Persistência Android (Room, fila e migrações): `docs/plans/android-local-persistence.md`
- Acesso à casa no Android (senha uma vez, Keystore, backup, reconexão): `docs/plans/android-house-access.md`
- Envio de comandos no Android (uma tentativa, recibos, teste contra Rails real): `docs/plans/android-command-transport.md`
- Ciclo de sincronização no Android (snapshot, fila ordenada, abrir/retomar, dois aparelhos, limites): `docs/plans/android-sync-cycle.md`
- Histórico de compras (leitura por mês, "Adicionar tudo de novo", API e tela nativa): `docs/plans/shopping-history.md`

Fluxo de entrega da migração: branch curta criada da `main` atualizada e PR
sempre para `main`, sem `staging`/`develop`/branch longa de migração; commits
pequenos e coerentes; uma entrega revisável por PR; dependência integrada à
`main` antes do trabalho dependente, sem empilhar PRs entre feature branches;
`Refs #issue` nos PRs intermediários e `Closes` só na conclusão da issue.

## Stack

- Ruby on Rails (app tradicional, MVC + views, não API-only)
- SQLite (banco padrão, sem necessidade de Postgres para este uso)
- Turbo/Hotwire (Turbo Streams + ActionCable) para atualização em tempo real
- Tailwind CSS
- PWA (manifest + service worker mínimo, via `rails/pwa`) — instalável direto do
  browser ("adicionar à tela inicial"). Decisão tomada na SHO-1 (histórico)
  depois de avaliar Ruby Native (SaaS pago) e Hotwire Native (exige toolchain
  Android/Kotlin). Hoje também existe um APK TWA com widget de inclusão e
  atalhos; o próximo passo é o app Android nativo offline (ver "Produto atual e
  destino nativo").
- Minitest + Capybara para testes (padrão Rails)

## Decisões de produto já fechadas (não reabrir sem confirmar com o usuário)

- **Acesso doméstico.** Sem contas individuais. Instalações privadas podem operar
  sem senha; publicação via Funnel exige senha compartilhada, cookie por
  aparelho e proteção de HTTP e ActionCable. Para configurar ou publicar, leia
  `docs/deployment/tailscale-funnel.md`. Decisão autorizada pelo usuário ao
  preparar o acesso sem VPN.
- **Sem modo offline (produto atual).** Uso majoritariamente em casa via wifi.
  O destino nativo offline está descrito em "Produto atual e destino nativo" e
  não altera o comportamento da PWA agora.
- **Sem quantidade nos itens.** Só o nome (ex.: "Leite", não "2x Leite").
- **Lista contínua.** Item comprado fica riscado mas visível, não vai para tela
  separada, e os itens pendentes sempre continuam na lista. O usuário pode
  registrar uma compra finalizada ("Compra feita", no rodapé da lista, só
  aparece com itens comprados): isso cria uma `ShoppingTrip` e **arquiva** os
  itens comprados (saem da lista viva, ficam no banco como histórico). Pendentes
  não são afetados.
- **Categorização automática sem IA.** Matching por nome normalizado contra um
  dicionário pré-cadastrado (`CatalogItem`). Sem match → categoria "Outros".
  O seed cobre a cesta brasileira (~330 nomes, sinônimos incluídos) e o
  `DictionaryRecategorizer` (também `rake dictionary:recategorize`) move, uma
  única vez por mudança de catálogo, itens que já estavam em "Outros" para a
  categoria que o dicionário agora conhece — nunca os que a casa escolheu à mão.
- **Categorias fixas** (criar/renomear categorias não existe). São 13, na ordem
  do `db/seeds/catalog.yml`: Hortifruti, Laticínios, Frios,
  Açougue, Congelados, Padaria, Mercearia, Bebidas, Limpeza, Higiene, Farmácia,
  Pet, Outros. "Congelados" reúne polpas de fruta e o que é claramente
  congelado (sorvete, açaí, pizza e lasanha congeladas, nuggets, pão de queijo
  congelado, legumes congelados); "Frios" ficou com o que é de
  frios e embutidos. O nome antigo "Frios e congelados" nunca existiu em
  produção, mas bancos de dev/teste antigos ainda o têm: o `db/seeds.rb`
  renomeia a categoria no lugar (mesmo id e `public_id`) antes de rodar o
  catálogo, de forma idempotente. O dicionário aprende: corrigir a categoria de
  um item à mão (lápis na linha) grava a entrada em `CatalogItem` com
  `learned: true` via `ItemCategorizer.learn`, e o `db:seed` (que roda a cada
  deploy) não sobrescreve entradas aprendidas.
  Digitar de novo um item comprado que ainda está na lista o traz de volta já
  na categoria atual do dicionário (`Household#add_item`); desmarcar o item
  pela caixinha mantém a categoria que ele tinha.
- **Corrigir item no lugar:** o lápis troca a linha, dentro de um Turbo Frame
  (`dom_id(item, :row)`), por nome + categoria (`ListItem#correct`). Nome novo
  sem categoria escolhida é recategorizado como se fosse digitado de novo.
  O botão **Remover** fica nesse formulário (não há × em cada linha); no
  celular, deslizar a linha para a esquerda também remove (`swipe_controller.js`,
  só toque e nunca em compras). Os dois passam pelo mesmo desfazer.
- **Deploy self-hosted**, não é para publicar na Play Store agora — distribuição
  é via PWA (acesso remoto via Tailscale). O APK TWA com widget já existe e a
  migração para o app Android nativo offline é o próximo passo (ver "Produto
  atual e destino nativo").
- Modelagem já prevê `Household` (mesmo com 1 registro fixo hoje) para não
  exigir retrabalho se o app virar produto multi-usuário no futuro.

## Modelagem de dados

```
Household
  └── has_many :list_items
  └── has_many :shopping_trips
  └── has_many :habitual_items
Category
  └── has_many :catalog_items
  └── has_many :list_items
CatalogItem   # dicionário: nome conhecido → categoria
  belongs_to :category
ListItem      # item real na lista de compras atual
  belongs_to :category (opcional — cai em "Outros")
  belongs_to :household
  belongs_to :shopping_trip (opcional — presente = item arquivado)
  status: pending | purchased
ShoppingTrip  # uma compra finalizada ("Compra feita")
  belongs_to :household
  has_many :list_items   # o que foi comprado nela
  finished_at (não nulo)
```

**Sincronização (issue #36):** todo registro sincronizado — casa, categoria,
  dicionário, item (arquivado inclusive), habitual e compra — tem `public_id`
  UUID único no banco, além do id inteiro que o Rails usa internamente.
  `households.sync_revision` numera as mudanças compartilhadas da casa e
  `SyncScope` (`"dictionary"`) numera o dicionário global; remoções gravam
  `SyncTombstone` com tipo, `public_id`, escopo, casa e revisão. `updated_at`
  nunca é cursor. Regras completas em
  `docs/plans/android-sync-contract-v1.md`.

**Snapshot (issue #37):** `GET /api/v1/snapshot` exige credencial `sync` e entrega
o estado completo da casa autenticada, com revisões e referências por UUID,
numa transação de leitura consistente. Formato, histórico e erros estão na
seção "Snapshot" de `docs/plans/android-sync-contract-v1.md`.

**Comandos idempotentes (issue #38):** `SyncCommandProcessor` executa comandos de
sync uma vez só. Cada comando que teve efeito (`applied`/`noop`) grava um
`SyncCommand` (UUID, casa, credencial e linhagem de origem, digest do conteúdo
canônico, resultado) na mesma transação do efeito, sob o lock da casa; conflito,
rejeição, bloqueio e exceção não deixam registro. `AndroidDevice#lineage_id`
sobrevive à reautorização quando o pareamento traz o `previous_token`. Os
registros nunca são podados. Cada comando registra seu executor em
`SyncCommandProcessor::EXECUTORS`. Regras em `docs/plans/android-sync-contract-v1.md`.

**Adicionar e marcar (issue #39):** `POST /api/v1/commands` (credencial `sync`)
executa lotes de comandos com `SyncCommands::AddItems` e
`SyncCommands::SetItemState`. `add_items` leva `payload.items: [{ public_id, name }]`
(o item nasce com o UUID do aparelho); o nome que o servidor já tem vira **alias**
do item canônico (`SyncAlias`, UUID reivindicável uma só vez em todas as casas,
buscas restritas à casa). `set_item_state` envia o estado desejado (nunca
toggle) e herda o `lock_version` do comando em `depends_on` quando este tocou o
item. Comprado só volta por `add_items` com `revives` da compra vista; item
arquivado/removido é `gone`. Executores rodam dentro da transação do ledger:
broadcast só depois do commit (`Household#broadcast_list_after_commit`; o
processador agrupa em um por comando).

**Arquivados:** um `ListItem` com `shopping_trip_id` é histórico e não faz parte
da lista viva. Toda query da lista viva (renderização, contadores, resumo,
detecção de duplicados, localizar item para marcar/remover) deve usar
`ListItem.on_list` (`shopping_trip_id` nulo); `ListItem.archived` é o histórico.
`Household#finish_shopping!` cria a viagem e arquiva os itens comprados numa só
query, faz um único broadcast e devolve a `ShoppingTrip` (ou `nil`, sem criar
nada, se não havia itens comprados). Nome, categoria e `purchased_at` dos
arquivados ficam intactos.

**Vários itens de uma vez:** o campo de adicionar aceita nomes separados por
vírgula, ponto e vírgula ou quebra de linha (`ItemNameSplitter`, máx. 30,
sem repetidos, cada nome com no máximo `ListItem::MAX_NAME_LENGTH` = 100
caracteres — o mesmo limite de `HabitualItem` e de `ListItem.name`).
`Household#add_items(texto)` passa cada nome por `add_item` e
devolve os `AddResult`s. Todo `ListItem` salvo chama `Household#list_changed`
(não `broadcast_list` direto); dentro de `Household#batch_list_broadcasts { }`
isso só marca a mudança e a lista é transmitida uma única vez no fim do bloco.
O corte de 100 é o mesmo em todos os caminhos: `ItemNameSplitter.clean` corta
o texto digitado (PWA e Android, com o aviso "Cada nome pode ter até 100
caracteres") e `Household#add_item_names` corta o que vem dos habituais e do
histórico, para o mesmo nome cair na mesma linha dos dois lados. O comando
`add_items` do sync **recusa** `invalid_name` / `reason: "too_long"` em vez de
cortar, e `correct_item` faz o mesmo, então `ListItem#validates :name,
length: { maximum: 100 }` (só quando o nome chega agora: um item salvo antes do
limite continua marcável, categorizável e arquivável) é a última barreira do
ledger.

**Comprar de novo:** abaixo do campo de adicionar, até 5 atalhos (`#buy_again`,
`BuyAgainSuggestions`) vêm do histórico arquivado, agrupados por nome normalizado
e ordenados por nº de compras e recência; nomes que estão na lista viva (pendentes
ou comprados) ficam de fora. Tocar num atalho passa por `ListItemsController#create`.
Os atalhos são atualizados junto com a lista em `list_items/_refresh`. Cadência
(#163): um nome com pelo menos 3 dias de compra no histórico ganha a mediana dos
intervalos entre dias de compra (um dia por viagem, no relógio da casa); quando
`hoje − último dia > mediana` ele vem **primeiro** na lista e leva o rótulo
"costuma acabar a cada ~N dias" com um "Agora não" que o esconde por 3 dias no
aparelho (`buy_again_controller.js` no localStorage, `DevicePreferencesStore` no
Android; nunca broadcast, nunca o servidor). O snapshot entrega `cadence_days` e
`due` de forma aditiva.

**Habituais:** `HabitualItem` guarda nomes escolhidos explicitamente por casa,
com unicidade por `household_id` + `normalized_name`. Não é o dicionário nem o
histórico. O painel recolhível fica abaixo de Comprar de novo; adicionar os
selecionados ou todos reaproveita `Household#add_item` e agrupa broadcasts, sem
o limite de 30 nomes do campo de texto. Remover um habitual não altera itens
da lista ou histórico. O refresh também atualiza `#habitual_items_list`; o
formulário, o painel aberto e as seleções sobreviventes são preservados.

**Duas telas, navegação local:** a página tem dois espaços, `#add_screen`
(Adicionar, a inicial: cabeçalho Alva/Nossa casa com menu ⋯, "Acabou de
adicionar", e embaixo, perto do polegar e do teclado, Comprar de novo, campo,
dica de vírgula e Habituais recolhido — sem a lista) e `#list_screen` (Lista de
compras: cabeçalho com busca visível e menu ⋯, resumo pendentes/comprados,
Iniciar compras, categorias, edição, desfazer e Compra feita). O menu ⋯
(`list_items/_menu`) reúne tema, cor, PDF (só na Lista) e Sair. A navegação inferior ("Adicionar" e "Lista" com `#list_counter` de
pendentes) troca de tela no cliente (`screen_controller.js`), sem request. No
celular ela é a mesma cápsula flutuante do Android (`FloatingNavigation`,
variante A): 16px das laterais, 8px acima da área segura, raio `--radius-capsule`
sobre `--color-surface` com a hairline de `--color-line`, `--shadow-float` e a
aba atual numa pílula `--radius-pill` sobre `--color-accent-soft`; não há barra
de borda a borda nem `backdrop-blur`. O que o conteúdo e o `#toast` deixam livre
embaixo é um único `--navigation-clearance` (área segura + 60px da cápsula + 8px),
e o desktop (≥1024px) mantém a coluna lateral. A
tela aberta é preferência do aparelho (cookie `screen`, sem broadcast); a
renderização inicial respeita o cookie e só dá autofocus no campo quando abre
em Adicionar. O antigo cookie `market_mode=true` abre a Lista até o aparelho
escolher uma tela (o modo mercado foi absorvido pela tela Lista). Broadcasts
atualizam as duas telas (escondida ou não), mas nunca navegação, tema ou o
retorno de adicionar: `#add_feedback` (live region) só é preenchido na resposta
HTTP de quem adicionou.

**Acabou de adicionar:** a resposta de adicionar (nunca o broadcast) preenche
`#recent_adds` com os itens criados ou revividos, cada um com a categoria num
seletor; trocar ali envia `PATCH list_items/:id` com `from=recent`, que corrige
e ensina o dicionário como o lápis e devolve a linha atualizada. Item que caiu
em "Outros" tem o seletor destacado. Cada linha que o último add **criou** tem um
"×" que remove pelo mesmo `DELETE list_items/:id` da Lista (`from=recent`): a
linha sai daqui e o `#toast` de quem agiu oferece "Desfazer", que a reinsere
(`POST /list_items/restore` com `from=recent`). Um nome **revivido** fica sem o
"×": é um comprado que voltou, e removê-lo apagaria histórico em vez do add. No
Android vale o mesmo (`AddScreenState#remove`, `RemoveItem`/`UndoRemove`, toast
do host) e o painel rápido confirma com a categoria ("Leite adicionado em
Laticínios") e oferece Desfazer por 3 s só para um nome criado. Enquanto se
digita, `duplicate_hint_controller.js`
avisa quais nomes já estão pendentes na lista (comparação sem acento/caixa via
`text_plain.js`, o par de `TextNormalizer.plain`); é só uma dica, o servidor
continua decidindo (e pega plurais que a dica não pega).

**Busca na Lista:** campo e "Só pendentes" sempre visíveis. A lupa foca o campo
sem limpar os filtros (`list_filter_controller.js`). No modo compras, o campo
fica fixo próximo ao rodapé, com "Só pendentes" junto dele; sair do modo limpa os
filtros e mantém a busca visível.

**Iniciar compras:** botão no resumo da Lista (só com itens na lista viva, também
quando todos já estão marcados) que a transforma em checklist. A preferência é do
aparelho, no cookie `shopping_mode=on` (24 h; separado do antigo `market_mode`),
gravado por `shopping_controller.js` e nunca transmitido. O servidor renderiza
`<html data-shopping-mode="on">` a partir do cookie (sem flash de controles nem
teclado) e `current_screen` passa a ser sempre `list`, sem mexer no cookie
`screen`. Todo o resto é CSS em `application.css` (`.shopping-hide`,
`.shopping-only`, navegação oculta): ficam só cabeçalho "Em compras" com resumo e
"Sair das compras", categorias, checkbox + nome em linhas maiores, "Compra feita"
e o toast com Desfazer. Como a marcação dos fragments de broadcast não depende do
cookie, lista atualizada por outro aparelho nunca traz controles de volta. Com um
formulário de correção aberto o botão fica `aria-disabled` com a dica "Salve ou
cancele a edição" (e o foco vai para o campo), para não perder o rascunho. Em compras,
cada categoria mostra quantos itens faltam ("2 restantes", "tudo pego"; fora de
compras só aparece "x/y" quando algo já foi comprado), "Compra feita" fica fixo
no rodapé, a tela fica acesa (Wake Lock API, pedido de novo ao voltar ao app) e
marcar vibra rapidamente quando o aparelho suporta. Iniciar
e sair só mudam a tela: nada é alterado nos itens nem finalizado. Compra feita
mantém o modo. `screen#show` ignora trocas de tela enquanto o modo está ativo.

**Marcar é otimista:** o toque na linha já mostra o estado pedido antes da
resposta (`mutation_controller.js`). O botão da linha (`_row`) troca de
`aria-pressed` na hora, a linha ganha `data-mutation-in-flight` e `aria-busy` e
fica a 60% de opacidade, e o `#mutation_progress` do layout anuncia "Marcado,
enviando" ou "Desmarcado, enviando" — as mesmas palavras do `pendingStatus` do
Android (`ui/ListScreen.kt`); a região fica fora da linha porque `aria-busy`
esconde as regiões vivas de dentro dela. Quem decide a aparência é o
`aria-pressed` no CSS, então o desenho confirmado continua sendo o do servidor.
Se a resposta falhar, o estado otimista é revertido antes do aviso. Um segundo
toque na mesma ação em voo é um no-op silencioso (sem o falso erro "Aguarde a
resposta…"); o aviso de espera só fica para uma tentativa anterior cujo
resultado ainda é desconhecido. O lápis de edição não precisa de JS: o Turbo
marca o `turbo-frame` da linha de `busy` enquanto o formulário de correção
carrega, e o mesmo CSS escurece a linha.

**Tema:** escuro (padrão) e claro, alternados pelo item do menu ⋯ em cada tela e salvos
por aparelho no cookie `theme` (`<html data-theme>` vem do servidor). As cores
são tokens semânticos em `app/assets/tailwind/application.css` (`canvas`,
`surface`, `line`, `ink`, `accent`…), redefinidos por `html[data-theme=light]`.
Views usam os tokens, não a paleta fixa do Tailwind (exceção: os fundos
translúcidos dos ícones de categoria, que funcionam nos dois temas).

**Tokens de dimensão:** raio, espaço, elevação, ícone e tamanhos de controle têm
uma escala única declarada no bloco `@theme` de `application.css` (`--spacing`,
`--radius-*`: `xs 8`, `control 12`, `card 16`, `pill 21`, `capsule 26`,
`sheet 28`; `--shadow-float`; `--size-icon-*` e os strokes; `--size-touch`
44px, `--size-check`, `--size-chip`; `--container-content`/`--container-search`)
e lida nas views por utilitários (`rounded-card`, `shadow-float`,
`size-icon-bar`, `text-title`, `text-row`). No Android os mesmos números vivem
em `ui/theme/AlvaDimens.kt` (`AlvaSpacing`, `AlvaRadius`/`AlvaShapes`,
`AlvaElevation`, `AlvaIconSize`, `AlvaSize`, também via `AlvaTheme.space` etc.)
e no XML em `values/alva_dimens.xml`. Nada de valor fracionário transliterado de
`rem` (`18.9.dp`, `22.4.dp`, `5.6.dp`): arredonde para o degrau da escala. O
`PwaThemeParityTest` compara as três camadas e falha quando uma diverge; ao
mudar uma escala, mude as três. Os alvos de toque continuam 44px na PWA e 48dp
no Android.

**Tokens de movimento:** a mesma ideia para o tempo, em `--duration-fast` (120ms,
a resposta direta ao toque: check, riscado, opacidade do "enviando"), `--duration-base`
(180ms, o estado que assenta: toast entrando, aba ativa, linha que muda de lugar),
`--duration-slow` (300ms, o movimento medido: barra de progresso das compras) e
`--duration-switch` (150ms, "Só pendentes"); easings `--ease-standard`
(`cubic-bezier(.2, 0, 0, 1)`, decelera) e `--ease-exit` (`(.4, 0, 1, 1)`, acelera
na saída). No Android os mesmos números estão em `AlvaMotion`
(`ui/theme/AlvaMotion.kt`, também via `AlvaTheme.motion`): `spec(enabled, …)` e
`springSpec(enabled, …)` devolvem `snap()` quando `ANIMATOR_DURATION_SCALE` é 0,
e as linhas da `LazyColumn` usam `animateItem` só com as specs quando o aparelho
anima — é assim que o teste de Compose não espera animação. Na PWA todo
`transition`/`animation` novo entra também no bloco `prefers-reduced-motion` do
fim de `application.css`. `MotionTokensTest` (Rails) e `PwaThemeParityTest`
(Android) cobram as duas coisas: duração tokenizada, easing tokenizado e o
desligamento nos dois lados.

**Desfazer:** remover um item e "Compra feita" não pedem confirmação; em vez disso
a resposta HTTP (nunca o broadcast, então só o aparelho que agiu) preenche o
`#toast` por ~5 s com "Desfazer". Remoção: o toast leva um `ListItemSnapshot`
assinado (`message_verifier(:undo)`, expira em 1 minuto) que `POST
/list_items/restore` reinsere com os atributos originais (`Household#restore_item`,
ignora se já há um pendente com o mesmo nome). Compra: `DELETE /shopping_trips/:id`
(`Household#undo_shopping!`, só a viagem mais recente) devolve os itens à lista e
apaga a viagem. Cada ação e cada desfazer fazem um único broadcast.

`CatalogItem` (o que o sistema "sabe") é separado de `ListItem` (o que está na
lista agora). Essa separação é o que permite categorização automática sem
acoplar o dicionário à lista viva.

## Convenções de código

- Nomes de classes/models/rotas em inglês (padrão Rails); conteúdo voltado ao
  usuário (labels, categorias, seeds) em português.
- Normalização de texto para matching (categorização automática e detecção de
  duplicados) deve reaproveitar uma única implementação — não duplicar lógica
  de "remover acento + downcase" em vários lugares. `TextNormalizer.plain` também
  lê um dígito como a letra parecida (`far0fa` → `farofa`, `b4n4na` → `banana`,
  `l3ite` → `leite`; `0→o`, `1→i`, `3→e`, `4→a`, `5→s`, `7→t`), mas só quando
  ele tem uma letra imediatamente antes **e** outra imediatamente depois. Um
  dígito na ponta da palavra ou ao lado de outra coisa que não seja letra fica
  como está: `vitamina d3`, `omega3`, `complexo b1`, `vitamina b5`, `v8`, `b12`,
  `7up`, `500g`, `coca 2l` e `leite 0%` não mudam. Fica em `plain` porque
  ordenação, dica de duplicado e categorização precisam
  concordar, e `call` já parte dele. No Android há um par Kotlin
  (`android/.../data/matching`, usado por telas e widget); os dois lados são
  verificados pelos mesmos exemplos em `test/fixtures/files/matching_corpus.json`.
  Mudou `TextNormalizer`, `ItemCategorizer`, `ItemNameSplitter` ou a regra de
  adicionar (`Household#add_one_item`)? Atualize o corpus e rode as duas suítes.
- Lógica de categorização vive em um service/PORO dedicado (`ItemCategorizer`),
  não direto no controller ou callback do model.
- UI mobile-first em duas telas (Adicionar e Lista, ver acima), substituindo a
  antiga tela única a pedido do usuário. Adicionar abre com o campo focado e
  dá retorno imediato; a Lista não abre teclado. Evitar modais, telas de
  confirmação ou navegação extra no fluxo principal de adicionar.
- Atualizações da lista via Turbo Streams / ActionCable broadcast — sem
  JavaScript customizado além do que Hotwire já oferece, a menos que
  estritamente necessário.

## Testes e validação

- **Durante a implementação:** crie ou ajuste testes do comportamento alterado
  e rode apenas os testes afetados e suas dependências diretas. Use os critérios
  da task (GitHub para a migração, Linear para o histórico) para escolher a cobertura.
- **Escolha pelo impacto:** Rails → testes dos models/services/controllers afetados;
  UI web → testes de sistema do fluxo alterado; Android → testes JVM das regras
  alteradas e testes instrumentados quando depender de Compose, Room, Keystore ou
  APIs Android. Mudança só no Android não exige suítes Rails locais, e vice-versa.
- **Integrações críticas:** sync/contrato/transporte → cenário pertinente contra
  Rails real; persistência/schema → testes de migração e preservação de dados;
  matching → corpus compartilhado nos dois lados, conforme a convenção acima.
  Rode somente os cenários pertinentes, ampliando a cobertura se aparecer risco
  em outros fluxos.
- **Documentação e comentários:** revise o diff e rode `git diff --check`.
  Comportamento inalterado dispensa suítes, build e emulador.
- **Antes de entregar o PR:** testes focados aprovados e diff revisado bastam
  para abrir o PR. As suítes completas ficam no CI existente e devem passar antes
  do merge. Informe o que foi validado localmente e o que está pendente no CI.
- **Ampliar localmente:** rode suítes completas quando o usuário pedir, houver
  mudança transversal sem cobertura focada suficiente ou falha indicar regressão
  além do escopo. Critérios específicos de uma entrega, como o aceite final da
  migração, continuam exigindo a validação correspondente.
- **Evitar repetição:** repita checks aprovados só após alteração relevante,
  falha ou dúvida concreta. Use o emulador para o comportamento que requer Android;
  gere APK para os celulares quando necessário à entrega ou solicitado. Serialize
  testes de browser e emulador neste host; encerre a sessão interativa de automação
  antes da instrumentação para evitar disputa de `UiAutomation`.

## Fora de escopo do produto atual (não implementar sem pedido explícito)

Para a PWA/APK conectado em uso hoje:

- Múltiplas famílias/contas e cadastro de usuários
- Quantidade por item
- Categorização com IA/fuzzy matching avançado
- Criar, renomear ou reordenar categorias via UI
- Notificações e integração com receitas
- Publicação na Play Store

Estas restrições descrevem o produto anterior. App Android nativo e modo offline
deixaram de ser fora de escopo: a migração foi autorizada (ADR 0001) e é
acompanhada pela issue #33, mantendo a PWA/APK atuais funcionando e sem novas
features fora da paridade.

O **histórico de compras** saiu de fora de escopo por decisão do dono em
18/11/2026 (issue #159): a tela "Compras" (menu ⋯, somente leitura, paginada por
mês) lista o que cada "Compra feita" arquivou e oferece "Adicionar tudo de novo"
reaproveitando `Household#add_items`; `GET /api/v1/history` entrega o mesmo
conteúdo ao app nativo, e repetir a compra usa o comando `add_items` que já
existia. Continuam fora de escopo editar o histórico e gerar gráficos a partir
dele. Detalhes em `docs/plans/shopping-history.md`.

**Histórico de compras:** `ShoppingHistory` lê as `ShoppingTrip` da casa, uma
página por mês do relógio da casa (`finished_at` em America/Sao_Paulo, não UTC),
mais recentes primeiro, e agrupa os itens de cada viagem por categoria como a
lista viva. `GET /compras` (menu ⋯ mostra "Compras") renderiza a tela e
`POST /compras/:id/adicionar` ("Adicionar tudo de novo") passa os nomes da
viagem por `Household#add_item_names`, com o mesmo resumo de adicionar no
`#history_feedback` do aparelho que agiu. Nada no histórico é editável. O app
nativo lê `GET /api/v1/history` (credencial `sync`, formato aditivo em v1) e
repete a compra pelo comando `add_items`, em lotes de 30 nomes.
