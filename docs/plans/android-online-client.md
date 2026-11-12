# Cliente online do Android (#94)

Acompanhamento: **#92**. Plano geral: [android-online-parity.md](android-online-parity.md).

O app nativo (variante debug) fala com o Rails como a PWA: **o servidor é a fonte
da verdade**. Não há Room na lista, fila, projeção nem resolução de conflito no
aparelho; nada é guardado para enviar depois. O código offline (Room, fila,
`SyncCycle`, `ConflictResolver`, painel de revisão) continua no repositório,
desligado da interface, e não deve ser ampliado.

## `OnlineRepository` (`android/app/src/debug/.../online`)

```
Tela ──execute(ação)──▶ OnlineRepository ──POST /api/v1/commands──▶ Rails
  ▲                           │  UUID novo por ação; retenta só falha de rede,
  │                           │  com o mesmo corpo (até 3 tentativas)
  └──── state ◀── GET /api/v1/snapshot ◀── ao abrir/retomar · sinal do ActionCable · após toda resposta
```

- `state: StateFlow<OnlineState>`: `Loading`; `Ready(snapshot, problem?)` com o
  snapshot inteiro do servidor (itens vivos, categorias, catálogo, habituais,
  arquivados, compras); `Failed(problem)` quando a primeira leitura falha.
  `OnlineProblem` traz o texto em português (sem rede, servidor indisponível,
  sem acesso, resposta inesperada). Se uma releitura falha, a lista que está na
  tela fica e `problem` avisa que pode estar desatualizada.
- `refresh()`: lê o snapshot (`HttpSnapshotTransport` + `SnapshotParser`).
  Pedidos que chegam durante uma leitura compartilham a seguinte, então uma
  rajada de sinais é uma leitura só. 401/403 chama `HouseAccess.reportUnauthorized`
  e o app oferece reconectar.
- `requestInvalidation()`: o `ForegroundInvalidation` (ActionCable) chama
  `refresh()`. Abrir/retomar a tela também.
- `suspend execute(action): ActionResult`: gera o UUID do comando, pede o
  documento à ação (a partir do **último snapshot**: `observed_revision`,
  `lock_version`, `revives`) e envia com `HttpCommandTransport`. Falha de rede
  ou timeout é retentada com os **mesmos bytes** (o servidor responde
  `duplicate` a um UUID que já guarda, então resposta perdida não duplica).
  5xx, 429, TLS e resposta ilegível não são retentados. Depois de **qualquer
  resposta** o snapshot é lido de novo; sem resposta (sem rede) não se tenta ler.

## Resultados (`ActionResult`)

| Resultado | Quando | Mensagem (`OnlineMessages`) |
| --- | --- | --- |
| `Success(receipt)` | `applied`, `noop` ou `duplicate` | — (ex.: `addedItems()` → "3 itens adicionados · Leite já estava na lista") |
| `Refused(code)` | `conflict`/`rejected`/`blocked` ou 4xx do envelope, com o código do contrato | "A lista mudou em outro aparelho…", "Este item não está mais na lista." |
| `Invalid(reason)` | a ação não faz sentido para a lista atual; nada é enviado | "Digite pelo menos um item." |
| `Offline` | sem rede depois das tentativas | "Sem conexão com o servidor. Nada foi salvo." |
| `ServerUnavailable` | 5xx, 429, TLS, resposta ilegível | "O servidor não está disponível agora…" |
| `NoAccess` | credencial ausente ou recusada | "Sem acesso à casa. Reconecte pelo menu ⋯." |

Nos resultados que não são sucesso a tela mantém o texto digitado e o estado
anterior: repetir a ação é o único reenvio.

## Ações cobertas hoje

- `AddItems` (`add_items`): nomes já separados (`AddItems.fromText` divide como o
  Rails: vírgula, ponto e vírgula, quebra de linha; até 30). Um nome que casa com
  um item **comprado** e nenhum pendente vai com `revives` (o item visto e seu
  `lock_version`), porque o servidor só traz uma compra de volta assim. O
  resto — categoria, duplicado — é decisão do servidor.
- `SetItemState` (`set_item_state`): estado desejado (nunca toggle) com o
  `lock_version` que o snapshot mostrou.

- `UndoItemState` (`undo_item_state`, #98): o "Desfazer" do toast depois de marcar
  ou desmarcar. Leva o **estado a restaurar** (o que o aparelho viu antes: status e
  instante original da compra) e, como precondição, o `lock_version` que a marcação
  deixou, lido de `data.item` do recibo do `set_item_state` (`CommandReceipt.markedItem()`).
  Só vale enquanto nada mexeu no item depois; senão o servidor recusa
  (`precondition_failed`) e a tela avisa que a lista mudou. O prazo de ~5 s é do
  toast, não do servidor.

## Habituais (#105)

`CreateHabitual`, `RemoveHabitual` e `AddHabituals` usam os comandos existentes do
Rails. A seleção manda UUIDs, sem o limite de 30 do campo de texto; adicionar
todos manda `all: true`, deixando o servidor decidir nomes, duplicados e revives.
Remover um habitual nunca remove o item da lista.

`HabitualPanelState`, mantido em `AddScreenState`, conserva rascunho, painel e
seleções sobreviventes durante releituras e navegação. O saver conserva esses
campos na recriação da Activity. Mensagens vêm somente do resultado local da
ação; adicionar habituais não preenche “Acabou de adicionar”.

## Como estender (#97–#109)

1. Crie uma classe que implemente `OnlineAction` (em `OnlineActions.kt` ou num
   arquivo novo do pacote). `plan(context)` devolve `ActionPlan.Send(documento)`
   — use `context.document(type, payload, preconditions)`, `context.snapshot` e
   `context.newId()` — ou `ActionPlan.Invalid(motivo)` sem enviar nada.
2. Precondições (`lock_version`, `dictionary_revision`, `trip_public_id`…) vêm do
   `context.snapshot`, nunca de estado guardado no aparelho.
3. Se precisar de um motivo novo de recusa local, acrescente-o a `InvalidAction`
   e o texto em `OnlineMessages`; códigos novos do servidor entram em
   `OnlineMessages.failure`.
4. O repositório, as tentativas, o recarregamento e os resultados não mudam.
   Para ler o resultado de uma ação, faça como `CommandReceipt.addedItems()`:
   uma função de extensão sobre o `receipt.dataJson`.
5. Teste com `FakeHouse` (servidor em memória com o ledger de idempotência, em
   `sharedTest`) e acrescente o cenário a `OnlineRailsConvergenceTest`.

## Ligação com a interface

`AccessGraph.onlineRepository(context)` cria um por processo com
`HttpSnapshotTransport` e `HttpCommandTransport` sobre `BuildConfig.SERVER_URL`
e a credencial do `HouseAccess`. `MainActivity` passa o repositório e o
`ForegroundInvalidation` a `ShoppingScreens`, que não mostra mais o painel de
sincronização nem o de revisão de conflitos. Ao conectar a casa não se agenda
mais o `SyncWorker`; o `AccessGraph` ainda o monta para os testes do offline.

## Telas (#98)

`ShoppingScreens` é só a casca: estado do repositório, ciclo de vida (reler ao
retomar, sinal ao vivo), cabeçalho da marca, a navegação inferior (`BottomNavigation`:
pílulas Adicionar/Lista, contador de pendentes) e o host do toast (`ToastMessage`,
~5 s, só no aparelho que agiu porque nasce do resultado da própria ação, nunca do
snapshot). A tela Lista vive em `ui/ListScreen.kt` (agrupamento e ordem da PWA em
`ListGroups`); ela já deixa os pontos de extensão das próximas issues: `headerActions`
(busca #101, menu ⋯ #109), `afterSummary` ("Iniciar compras" #102, painel de busca) e o
`trailing` de `ListRow` (lápis #100, remover/swipe #99). Novos toasts (remover, compra
feita) reutilizam `ToastMessage`.

## Modo compras (#102)

`ShoppingMode` lê a preferência local de 24 horas antes de compor as telas e a
revalida ao retomar. O modo força a Lista, esconde navegação, personalização,
edição e swipe; a saída limpa filtros. A busca fica no rodapé, sem autofocus.
`LocalView.keepScreenOn` mantém a tela acesa enquanto o modo está ativo;
marcações aceitas no aparelho pedem feedback háptico pela API do Android.
A barra de progresso e os contadores vêm do snapshot inteiro, mesmo com busca.
O modo não envia comandos nem finaliza uma compra; a finalização é a #103.

## Finalizar e desfazer compra (#103)

`FinishShopping` recebe os itens observados na tela e envia somente os comprados,
com UUID e versão. Se qualquer um mudou, o Rails recusa o lote inteiro. O recibo
fornece o UUID da viagem para `UndoShopping`; o servidor verifica que ela ainda
é a mais recente. Pendentes e compras posteriores à observação são preservados.

“Compra feita” aparece no fim da lista normal e acima da busca no modo compras,
somente com comprados. Durante o envio fica desabilitado; sucesso gera o toast
local “Compra registrada” com Desfazer, sem mudar o modo compras.

## Menu e preferências (#109)

`DevicePreferencesStore` usa Preferences DataStore para tema, cor e última tela.
`MainActivity` aguarda a primeira leitura antes de desenhar; a paleta também
atualiza as barras do sistema. Navegar grava a tela escolhida, enquanto o modo
compras apenas força a Lista e preserva essa escolha. Sair limpa o acesso local,
sem apagar as preferências.

`PdfClient` faz `GET /api/v1/list.pdf` com bearer de sync, sem redirecionamentos,
com limite de 8 MiB e validação do cabeçalho PDF. 401/403 invalida o acesso;
erros não expõem corpos do servidor. `PdfShare` grava um arquivo temporário em
`cache/list_exports` e oferece compartilhar ou abrir com uma URI `content://`
e permissão de leitura. A extensão do FileProvider fica só no debug, restrita a
esse diretório; outros arquivos de cache não são compartilhados. Arquivos de
exportação com mais de um dia são limpos ao preparar o próximo PDF.

## Widget e painel rápido (#110)

O provider e seu PendingIntent continuam apontando para `QuickAddActivity`.
A implementação Java do Alva 1.3 fica no source set `release`, intacta; o debug
fornece uma Activity Kotlin nativa com o mesmo nome. Isso preserva widgets
instalados e evita um segundo componente para a mesma ação.

O painel reutiliza o layout rosa do Alva 1.3 (inclusive a confirmação e a janela
no rodapé), independentemente da personalização das duas telas principais.
`QuickPanelModel` usa `HouseAccess` e `OnlineRepository` da aplicação. Um ViewModel
mantém o envio durante recriação; somente o rascunho vai ao SavedStateHandle,
sem fila, comando persistido ou reenvio automático após morte do processo.
Sucesso fecha após 1,5 s; falha conserva texto e permite nova tentativa. Sem
acesso, o painel não pede senha: oferece abrir o app principal. A transição de
release da #111 deve promover também essa implementação nativa do painel.

## Validação

- JVM: `OnlineRepositoryTest` (servidor falso: leitura, rede caindo, resposta
  perdida que não duplica, recusa, 5xx, sem acesso, revive).
- Instrumentado: `ShoppingScreensTest` (tela sobre o repositório: texto mantido
  sem rede, retomar e sinal ao vivo recarregam; a Lista: seções, "x/y", contador,
  marcar, toast e desfazer) e `MainActivityAccessTest`.
- Rails real: `script/android_rails_online_check` — dois "aparelhos"; A adiciona
  e marca, B vê; mesmo nome nos dois; marcação velha recusada; desfazer marcação
  (e desfazer recusado quando o outro aparelho mexeu); resposta perdida
  depois do commit retentada sem segundo efeito. Roda no CI junto do
  `android_rails_sync_check`.
