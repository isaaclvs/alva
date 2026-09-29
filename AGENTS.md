# AGENTS.md — Shopping List App

Contexto para qualquer agente de código trabalhando neste repositório.

## O que é o projeto

App Android de lista de compras compartilhada, para uso doméstico (2 usuários).
Foco absoluto: adicionar um item que acabou em casa precisa ter **fricção zero**.
Quando chega o dia de ir ao mercado, a lista já está organizada por categoria.

Rastreamento de trabalho: Linear, time **SHO** (https://linear.app/diff-review/team/SHO).
As tasks do MVP (SHO-1 a SHO-10) já descrevem contexto, critério de aceite e notas
técnicas — leia a task correspondente antes de implementar.

## Stack

- Ruby on Rails (app tradicional, MVC + views, não API-only)
- SQLite (banco padrão, sem necessidade de Postgres para este uso)
- Turbo/Hotwire (Turbo Streams + ActionCable) para atualização em tempo real
- Tailwind CSS
- PWA (manifest + service worker mínimo, via `rails/pwa`) — instalável direto do
  browser ("adicionar à tela inicial"), sem app nativo/APK por enquanto. Decisão
  tomada na SHO-1 depois de avaliar Ruby Native (SaaS pago) e Hotwire Native
  (exige toolchain Android/Kotlin) — nenhum dos dois valia a pena pra meta atual
  de teste rápido com o usuário real.
- Minitest + Capybara para testes (padrão Rails)

## Decisões de produto já fechadas (não reabrir sem confirmar com o usuário)

- **Sem autenticação.** App doméstico, self-hosted, uso restrito a 2 pessoas.
- **Sem modo offline.** Uso majoritariamente em casa via wifi.
- **Sem quantidade nos itens.** Só o nome (ex.: "Leite", não "2x Leite").
- **Lista contínua**, não por "compra fechada". Item comprado fica riscado mas
  visível, não some e não vai para tela separada.
- **Categorização automática sem IA.** Matching por nome normalizado contra um
  dicionário pré-cadastrado (`CatalogItem`). Sem match → categoria "Outros".
- **Categorias fixas no MVP** (editar dicionário/categorias é backlog futuro).
- **Deploy self-hosted**, não é para publicar na Play Store agora — distribuição
  é via PWA (acesso remoto via Tailscale). App nativo/APK fica em aberto para o
  futuro, se fizer sentido depois do teste com o usuário real.
- Modelagem já prevê `Household` (mesmo com 1 registro fixo hoje) para não
  exigir retrabalho se o app virar produto multi-usuário no futuro.

## Modelagem de dados

```
Household
  └── has_many :list_items
Category
  └── has_many :catalog_items
  └── has_many :list_items
CatalogItem   # dicionário: nome conhecido → categoria
  belongs_to :category
ListItem      # item real na lista de compras atual
  belongs_to :category (opcional — cai em "Outros")
  belongs_to :household
  status: pending | purchased
```

`CatalogItem` (o que o sistema "sabe") é separado de `ListItem` (o que está na
lista agora). Essa separação é o que permite categorização automática sem
acoplar o dicionário à lista viva.

## Convenções de código

- Nomes de classes/models/rotas em inglês (padrão Rails); conteúdo voltado ao
  usuário (labels, categorias, seeds) em português.
- Normalização de texto para matching (categorização automática e detecção de
  duplicados) deve reaproveitar uma única implementação — não duplicar lógica
  de "remover acento + downcase" em vários lugares.
- Lógica de categorização vive em um service/PORO dedicado (`ItemCategorizer`),
  não direto no controller ou callback do model.
- UI mobile-first, tela única no MVP: campo de adicionar item com foco
  automático no topo, lista abaixo. Evitar modais, telas de confirmação ou
  navegação extra no fluxo principal de adicionar.
- Atualizações da lista via Turbo Streams / ActionCable broadcast — sem
  JavaScript customizado além do que Hotwire já oferece, a menos que
  estritamente necessário.

## Testes

- Toda task deve incluir testes (model, request/controller ou sistema/Capybara,
  conforme o critério de aceite da task no Linear).
- Rodar a suíte completa antes de considerar uma task pronta: `bin/rails test`
  (e `bin/rails test:system` quando houver testes de sistema).

## Fora de escopo do MVP (não implementar sem pedido explícito)

- Múltiplas famílias/contas, login
- Quantidade por item
- Categorização com IA/fuzzy matching avançado
- Edição de categorias via UI
- Histórico de compras, notificações, integração com receitas
- Publicação na Play Store / app nativo
- Modo offline
