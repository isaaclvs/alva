# Persistência local do Android (Room) — issue #41

Status: **implementado na variante debug**; a release (TWA) não inclui Room até
a transição. Acompanhamento: [#33](https://github.com/isaaclvs/alva/issues/33).
Regras de sync em [android-sync-contract-v1.md](android-sync-contract-v1.md);
este documento só descreve o lado local e registra lacunas do contrato.

## Onde está o código

`android/app/src/debug/java/net/ts/taild80ca0/atlas/twa/data/`. Room, KSP e
corrotinas entram só como `debugImplementation`/`kspDebug`; a tarefa
`verifyReleaseHasNoLocalPersistence` (parte de `verifyVariants`) falha se
`androidx.room` aparecer na release. Ao migrar a entrada padrão, o código sobe
para `src/main` e as dependências para `implementation`.

## Três camadas

| Camada | Tabelas | Quem escreve |
| --- | --- | --- |
| Estado confirmado | `sync_state`, `categories`, `catalog_items`, `catalog_state`, `confirmed_items` | Só a reconciliação (#45), nunca uma ação do usuário |
| Projeção local | `list_items` | Cada ação, na mesma transação do comando; ou `rebuildProjection()` |
| Fila | `commands`, `command_subjects` | Cada ação |

- Todo registro sincronizado é chaveado pelo `public_id` (UUID) do servidor.
  Item criado offline já nasce com UUID do aparelho (`command_subjects`,
  papel `creates`, `ordinal` = índice em `names`).
- `commands.sequence` (AUTOINCREMENT, nunca reaproveitado) é a **ordem por
  aparelho**; `commands.id` (UUID, índice único) é a chave de idempotência.
  Comando só sai da fila quando confirmado; não há expiração.
- `depends_on` é o último comando ainda na fila que toca o mesmo registro. Se
  esse comando está em `CONFLICT`, `REJECTED` ou `BLOCKED`, o novo nasce
  `BLOCKED` (preserva a intenção); comandos sobre outros registros seguem
  `PENDING`.
- `rebuildProjection()` recalcula `confirmado + fila` com o mesmo
  `ProjectionReducer` das ações; assim uma leitura remota nunca apaga a fila.

## Interface única

`ShoppingListStore` (`observe(): Flow<ListState>`, `execute(ListIntent)`).
Telas e widget só usam isso. `Saved` só é devolvido depois do commit de
projeção **e** comando; `Invalid`/`Failed`/`Unchanged` não gravam nada, então
quem chama mantém o texto digitado e não mostra sucesso. Cada emissão do
`observe` é uma leitura numa transação. Intents desta task: `AddItems` e
`SetItemState` (estado desejado, nunca toggle); as demais (corrigir, remover,
finalizar, habituais) chegam com as tasks próprias.

Fora de escopo aqui: telas (#43), pareamento (#44), envio e reconciliação
(#45), widget (#56). Categorização e duplicados locais: seção seguinte (#42).

## Categorização e duplicados locais (issue #42)

Código em `data/matching` (puro Kotlin: `TextNormalizer`, `ItemNameSplitter`,
`ItemCategorizer`, `DuplicateHint`), `data/list/AddPlanner` e `data/catalog`
(`LocalCatalog`). Telas e widget usam essa única implementação, via
`ShoppingListStore`; nenhuma regra de texto é copiada para a UI.

- **Corpus compartilhado:** `test/fixtures/files/matching_corpus.json`
  (normalização, tabela de transliteração, categorização, divisão e regras de
  adicionar). O Ruby o executa em `test/services/matching_corpus_test.rb`; o
  Kotlin, em `MatchingCorpusTest` (JVM) e `MatchingCorpusStoreTest`
  (instrumentado, passando pelo Room). Exemplo novo vai no JSON e roda nos dois
  lados; as expectativas são escritas à mão, não geradas por uma implementação.
- **Transliteração além de NFD:** o servidor usa `I18n.transliterate`, que
  conhece ß, æ, ø, ł, œ… e troca por `?` o que não conhece. O Kotlin copia essa
  tabela (`Transliteration`), não usa NFD. O Ruby afirma que a tabela do corpus
  é a do gem i18n; o Kotlin afirma que a sua é a do corpus. Uma atualização do
  gem que mude a tabela quebra um teste até as duas pontas serem atualizadas.
- **Comportamentos herdados do Ruby, de propósito:** acento decomposto (NFD) e
  NBSP viram `?`; só espaço ASCII separa palavras; `strip` tira NUL e espaço
  ASCII, não NBSP; um emoji ou sequência ZWJ conta por code point. Estão no
  corpus com `note`. Mudar isso exige mudar o servidor (e os `normalized_name`
  gravados), não só o cliente.
- **Categorias do servidor:** `categories` e `catalog_items` (com o
  `normalized_name` do próprio servidor) são trocados por inteiro por
  `LocalCatalog.replace`, numa transação, com `dictionary_revision` em
  `catalog_state`. Snapshot com revisão menor que a gravada não faz nada. Sem
  correspondência, a categoria é a de nome "Outros" **sincronizada**; antes da
  primeira carga não há categoria nenhuma e o item fica sem categoria. Nenhum
  UUID de categoria é inventado, e uma entrada apontando para categoria
  desconhecida é ignorada.
- **Servidor é a autoridade:** trocar o dicionário não recategoriza itens que já
  estão na lista (a escolha manual de uma categoria, #47/#48, continua valendo);
  entradas `learned` do servidor entram como qualquer outra. A categoria
  automática de um item ainda não confirmado pode ser atualizada pela
  reconciliação (#45) segundo o dicionário do servidor.
- **Adicionar (`AddPlanner`):** mesma regra de `Household#add_one_item`,
  sequencial (um nome vê o efeito dos anteriores): pendente de mesmo nome
  normalizado → duplicado; senão o comprado mais recente é revivido (mantém o
  próprio nome, categoria recalculada pelo dicionário atual); senão cria, já
  categorizado. Itens arquivados não contam. `AddText` divide como o campo de
  texto (vírgula, ponto e vírgula, quebra de linha; sem repetidos; **máx. 30**);
  `AddItems` recebe nomes já separados (habituais) **sem** esse limite.
- **Resultado e comando:** `Saved` traz `additions` (criado/revivido/duplicado
  por nome). Se tudo já estava pendente, `AlreadyOnList` e nada é gravado nem
  enfileirado. O comando continua `add_items` com `payload.names`, só com os
  nomes que mudam a lista (criados e revividos). Esse é o formato local legado,
  a ser adaptado para `payload.items` na #45; o que o aparelho decidiu fica
  em `command_subjects` (`creates`/`targets` + `category_public_id`), metadado
  local que não vai no fio. UUIDs e comandos já enfileirados não mudam;
  `rebuildProjection` reproduz exatamente a projeção.
- **Dica ao digitar:** `DuplicateHint` é o par Kotlin de
  `duplicate_hint_controller.js` (compara com `TextNormalizer.plain`, sem
  plural); só dica, a decisão é do `AddPlanner`/servidor.

## Documento de envio congelado (issue #45, primeiro corte)

Código: `data/list/WireOutbox.kt` e `WireDocument.kt`; coluna
`commands.wire_document` (nula por padrão). **Não há rede neste corte.**

- **Intenção ≠ envio.** `commands.payload`/`preconditions` são a intenção local,
  que o `ProjectionReducer` reaplica (`rebuildProjection`). `wire_document` é o
  JSON de **um comando** como o contrato v1 o envia — `id`, `type`, `depends_on`,
  `preconditions`, `payload` e, se houver, `observed_revision` — preparado uma vez
  e guardado byte a byte. Nunca é reconstruído da projeção ou da intenção atual.
- **Por quê.** O servidor compara o conteúdo canônico do comando com o digest do
  UUID já visto (`idempotency_conflict`); um reenvio com conteúdo diferente do
  primeiro envio perde a idempotência. O documento é apagado junto com o comando
  quando o servidor confirma (mesma linha).
- **`freeze(commandId, candidato)`** (transação): só grava se não houver documento
  e o comando nunca tiver sido tentado (`attempts = 0`), com a guarda também no
  SQL (`UPDATE … WHERE wire_document IS NULL AND attempts = 0`), então dois
  consumidores concorrentes têm um único vencedor. O candidato precisa trazer o
  mesmo `id`, `type` e `depends_on` do comando local, ter só campos do contrato e
  o formato do tipo (`items` em `add_items`, `desired` em `set_item_state`); o
  formato legado `payload.names` é recusado. Com documento já congelado, qualquer
  candidato posterior (válido ou não) é ignorado e o original volta.
- **`beginAttempt(commandId)`** (transação): soma `attempts`, passa o comando a
  `IN_FLIGHT` e devolve o documento congelado; um futuro remetente só põe bytes na
  rede depois do commit. O remetente deve chamar esse método **fora de qualquer
  transação Room externa** e aguardar o retorno; não envolver tentativa e HTTP
  numa transação maior. Se houver transação externa, `Started` ainda é provisório
  até o commit dela (o teste de rollback cobre esse caso). Recusa comando sem documento, inexistente ou
  `BLOCKED`/`CONFLICT`/`REJECTED`. `IN_FLIGHT` pode ser tentado de novo (processo
  morto no meio). Erros de SQL propagam e desfazem tudo, inclusive dentro de uma
  transação maior do chamador.
- **Fila legada.** Comando com `attempts > 0` e sem documento (anterior a esta
  versão) pode ter saído com bytes que não foram guardados: `freeze` e
  `beginAttempt` recusam com `LEGACY_ALREADY_ATTEMPTED`, preservando fila,
  intenção, projeção e contadores; nada é inventado. Com `attempts = 0` e sem
  documento a linha fica intacta (`NOT_FROZEN` ao tentar), disponível para a
  conversão (feita no corte seguinte, ver "Preparação do envio"). `payload.names`
  continua sendo só intenção local: `freeze` recusa o formato legado, e só um
  documento do `WirePreparer` (ou equivalente validado) é enviável.
- **Não faz** (próximos cortes da #45): HTTP/transporte, snapshot e
  reconciliação, ciclo de vida (abrir/retomar, WorkManager), tratamento de
  resultados (`duplicate`, `conflict`, `blocked`), aliases devolvidos, decisão para
  a fila legada tentada e validação em dois aparelhos. A conversão da intenção em
  documento é a seção seguinte.

## Preparação do envio (issue #45, segundo corte)

Código: `data/list/WirePreparer.kt` (Room, transação), `WireEnvelopeBuilder.kt`
(puro: intenção → documento), `ReviveObservation.kt` (o que o usuário viu) e
`WireItemLinks.kt` (documento congelado ↔ subjects). **Continua sem rede, sem
envio automático e sem ciclo de vida**: nada aqui chama o servidor.

`WirePreparer.prepare(commandId)` é uma única transação Room (junta-se à do
chamador, se houver):

1. comando inexistente → `UNKNOWN_COMMAND`;
2. **já tem `wire_document` → devolve exatamente esses bytes** (`created = false`),
   seja qual for o status ou o número de tentativas. Nada é reconstruído, nenhum
   UUID é gerado, nem se uma preparação nova seria recusada;
3. `attempts > 0` sem documento → `LEGACY_ALREADY_ATTEMPTED`; status
   `BLOCKED`/`CONFLICT`/`REJECTED` → `NOT_SENDABLE`;
4. senão `WireEnvelopeBuilder` monta o documento **só da intenção guardada**
   (`payload`, `preconditions`, `command_subjects`, `depends_on`,
   `observed_revision`) e `WireOutbox.freeze` o grava. Nunca lê a projeção
   (`list_items`) nem um snapshot. Duas preparações simultâneas são serializadas
   pela transação e pela guarda SQL: as duas recebem o mesmo documento e só
   existe a reivindicação do vencedor. Erro de SQL propaga e desfaz tudo.

**`add_items`.** `payload.names` + `command_subjects` viram `payload.items`; o item
`i` do documento é o subject com `ordinal = i`.

- Nome que **cria**: vai com o UUID que o aparelho já tinha dado (`creates`), o
  mesmo em qualquer retentativa.
- Nome que **revive** uma compra (`targets`): o `public_id` da entrada é um **UUID
  novo de reivindicação**, gerado uma vez e gravado no documento congelado, e
  `revives.public_id` é o item comprado observado. Nunca se reutiliza o UUID da
  compra como `public_id` da entrada: o Rails trata UUID conhecido como duplicado
  e não reviveria nada (teste Rails em `android_wire_documents_test.rb`). O servidor
  transforma a reivindicação em alias da compra; `WireItemLinks.of(documento,
  subjects)` devolve, por ordinal, o UUID enviado, o subject local e o revivido,
  para quem aplicar o resultado (e recusa — `null` — se não se alinharem).
  `WireDocument` também recusa, ao congelar, um revive cuja entrada repete o UUID
  da compra ou com `lock_version` inválido.
- O nome enviado é o digitado; o servidor normaliza e categoriza (a categoria
  local não vai no fio).
- `observed_revision` do comando entra só se > 0 (0 é "nada observado"); o
  servidor não o usa como precondição para estes comandos.

**`set_item_state`.** `desired` (nunca toggle), `preconditions.lock_version` se a
intenção o guardou, e o `depends_on` do próprio comando.

### O que o usuário viu, na hora da intenção

Para um revive novo, `RoomShoppingListStore` grava em `commands.preconditions`
(JSON de intenção, **sem mudar o schema**; o `ProjectionReducer` o ignora, então o
replay e `rebuildProjection` são os mesmos):

```json
{"revives":[{"ordinal":1,"public_id":"<compra>","lock_version":3,"covered":false}]}
```

- `lock_version` é o da projeção no instante da ação (nulo se o aparelho nunca viu
  o item no servidor). Um snapshot aplicado depois **não o altera**: o servidor
  compara exatamente essa versão para decidir `purchase_changed`. Testado: intenção
  com versão 3, snapshot 8 aplicado antes de preparar, documento com 3.
- `covered` registra que o `depends_on` do comando é o último comando da fila que
  tocou aquele item, ou seja, o servidor pode herdar a versão que ele deixou
  (adicionar offline → marcar → digitar de novo não precisa de versão).
- Nenhuma versão é inventada ou incrementada no aparelho, e nenhuma é lida da
  projeção atual na preparação.

Decisão de armazenamento: o JSON de intenção atende (por ordinal, junto do
comando, sem coluna nova), então o banco **segue na versão 3**; `schemas/1-3.json`
não mudam e não há migração 3→4. Se uma coluna for necessária mais tarde (p.ex.
ao ingerir o snapshot), segue a política de migração acima.

### Recusas (saídas que a reconciliação futura deve resolver)

Toda recusa é um valor (`PreparationResult.Refused`), não grava nada, não gasta
UUID e preserva fila, intenção, subjects, projeção e contadores. O comando não é
descartado nem reinterpretado, e nenhum pedido incompleto é enviado.

| Motivo | Quando | Saída futura |
| --- | --- | --- |
| `LEGACY_ALREADY_ATTEMPTED` | `attempts > 0` sem documento (anterior ao 2→3): os bytes enviados não foram guardados | snapshot, ver o que foi aplicado e reemitir com UUIDs novos (contrato) |
| `REVIVE_OBSERVATION_LOST` | revive anterior a este corte (sem registro do que foi visto) e o `depends_on` não toca o item | snapshot mostra a compra atual; a intenção é reavaliada com UUID novo |
| `VERSION_UNKNOWN` | a versão vista é nula (item só criado neste aparelho) e não há comando anterior para herdar; vale para `set_item_state` | snapshot que traga o item (reconciliação) |
| `DEPENDENCY_INSUFFICIENT` | há `depends_on`, mas ele não toca o item sem versão (p.ex. revive de dois itens com cadeias diferentes) | snapshot com o item |
| `ENVELOPE_TOO_LARGE` | `add_items` com mais de 30 nomes (só nasce de `AddItems`, p.ex. habituais, #53; `AddText` já limita a 30 e sai normalmente) | uma tarefa que divida em comandos de forma explícita, nunca em silêncio; nenhum nome é descartado |
| `NOT_SENDABLE` | `BLOCKED`/`CONFLICT`/`REJECTED` | resolução do conflito/bloqueio |
| `UNSUPPORTED_TYPE` | tipo sem forma de fio ainda | a tarefa que o introduz |
| `INCONSISTENT_INTENT` | payload, precondições e subjects não se correspondem | reparo; linha danificada |
| `INVALID_DOCUMENT` | o documento montado não passou na validação de congelamento | defeito do código, não do dado |

Limite conhecido: a evidência `covered` e as linhas de dependência vêm da fila.
Um comando legado cuja dependência já foi confirmada e removida não pode provar
que ela tocava o item; é recusado (`REVIVE_OBSERVATION_LOST`) em vez de supor.

### Fixtures compartilhadas com o Rails

`test/fixtures/files/android_wire_documents.json` guarda os documentos que o
cliente congela para cada intenção. O Kotlin (JVM e instrumentado) monta os mesmos
a partir de intenções guardadas, e `test/controllers/api/v1/android_wire_documents_test.rb`
os envia ao `POST /api/v1/commands` real e confere o que o Rails responde
(`applied`, `revived`, alias). Isso mostra que o Rails aceita essas formas; por si
só **não é o cliente Android falando com o servidor** e não prova convergência. O
cliente de transporte contra o Rails real é o `script/android_rails_transport_check`
(ver [android-command-transport.md](android-command-transport.md)). O servidor segue como autoridade: a validação estrutural do cliente
não substitui as regras do Rails.

## Durabilidade e migrações

- O banco abre com `PRAGMA synchronous = FULL`: uma ação confirmada ao usuário
  sobrevive à morte do processo e, em geral, à queda de energia.
- **Política:** nunca `fallbackToDestructiveMigration`; migração ausente faz a
  abertura falhar (dados intactos). Toda mudança de versão traz, juntos:
  `schemas/<versão>.json` gerado pelo Room (no Git), `Migration` encadeada em
  `AlvaMigrations.ALL` sem lacunas e teste de migração com linhas nas tabelas
  antigas. `AlvaMigrationsTest` (JVM) e `AlvaDatabaseMigrationTest`
  (instrumentado) cobrem a cadeia e a existência dos schemas. Preferir passos
  aditivos.
- A versão atual é **3** (`MIGRATION_2_3`, issue #45: acrescenta `commands.wire_document`
  nulo; veja a seção anterior). `MIGRATION_1_2` (issue #42) é aditiva: cria
  `catalog_items` (índice único em `normalized_name`) e `catalog_state`, e
  acrescenta `command_subjects.category_public_id` (nulo para o que já estava na
  fila). Lista, fila, UUIDs e `schemas/1.json` ficam intactos.
  `AlvaDatabaseMigrationTest` migra um v1 com linhas em todas as tabelas,
  confere cada uma depois, valida o schema contra `2.json` e reconstrói a
  projeção sobre a fila migrada. O mesmo teste migra um v2 com linhas em todas as
  oito tabelas para o v3, compara cada linha antes/depois (exceto a coluna nova,
  nula), valida contra `3.json` e abre pela fábrica. `schemas/1.json` e `2.json`
  não mudam.

## Testes

- JVM (`testDebugUnitTest`): `ProjectionReducerTest`, `AlvaMigrationsTest`,
  `WireDocumentTest`, `WireEnvelopeBuilderTest`,
  `MatchingCorpusTest`, `ItemCategorizerTest`, `AddPlannerTest`,
  `DuplicateHintTest`.
- Instrumentados (`connectedDebugAndroidTest`, job `android-instrumented` do
  CI): atomicidade (incluindo falha no enqueue revertendo a projeção, por UUID
  repetido e por trigger que recusa o INSERT), dependências e bloqueio,
  ordenação e concorrência, reabertura do arquivo, `synchronous=FULL`,
  migrações (incluindo 1→2 com dados), categorização/duplicados sobre Room
  (`LocalMatchingStoreTest`), o corpus compartilhado pelo store real
  (`MatchingCorpusStoreTest`) e o documento congelado (`WireOutboxTest`:
  reabrir e repetir byte a byte, candidato/projeção alterados, legado recusado,
  falha de SQL revertendo, dois consumidores) e a preparação (`WirePreparerTest`:
  fixtures do Rails a partir de intenções reais, reabrir e repetir, revive com a
  versão da intenção, recusas que preservam a fila, legados, corrida de duas
  preparações, falha de SQL e transação externa). Rodar localmente exige emulador/aparelho:
  `./gradlew connectedDebugAndroidTest` em `android/`.
- Não coberto: matar o processo de verdade e reiniciar o aparelho (simulado
  lendo o arquivo por outra conexão sem fechar o escritor); fica para a
  validação nos aparelhos (#59).

## Lacunas observadas no contrato v1 (para #37/#38/#39/#45)

Nenhuma regra de servidor foi inventada; o cliente guarda o necessário para
qualquer das saídas. Situação após a #39 (detalhes em
[android-sync-contract-v1.md](android-sync-contract-v1.md#comandos-v1)):

1. **Fechada (#39).** `add_items` leva `payload.items: [{ public_id, name }]`, na
   ordem de `names` do rascunho. O cliente monta cada entrada com o UUID que já
   guarda em `command_subjects` (papel `creates`, `ordinal` = índice) e a
   resposta traz, por item, `public_id` (o do aparelho) e `canonical_public_id`
   (o do servidor; diferente = alias). O cliente deve registrar o alias ao
   reconciliar. O campo antigo `names` não existe mais no fio.
2. **Fechada (#39).** O cliente omite `preconditions.lock_version` de um comando
   sobre item que o servidor ainda não viu e depende do comando que o criou; o
   servidor aceita e usa a versão que aquele comando deixou.
3. **Fechada (#39).** Um comando com `depends_on` aplicado herda a versão
   resultante *daquele* comando (do ledger); o conteúdo do comando enviado nunca
   muda. Se o comando também tem uma versão explícita de snapshot, usar a
   maior das duas: a versão do snapshot pode ser mais recente que o recibo.
   Primeiro passo sobre item
   conhecido de um snapshot leva o `lock_version` do snapshot. Atenção ao
   `depends_on`: precisa ser o último comando da fila **sobre o mesmo item** (é o
   que o Room já faz); um que só tocou outros itens não empresta versão.
   Para trazer de volta um item comprado digitando o nome de novo, o item de
   `add_items` leva `revives: { public_id, lock_version }` da compra vista
   localmente; pode omitir `lock_version` quando `depends_on` fornece a versão
   desse mesmo item. Isso cobre adicionar → marcar → digitar de novo sem rede
   (ou o cliente usa `set_item_state` pending sobre o item).
4. **Aberta** (`correct_item`, #47/#48): usa `category_id` (inteiro) enquanto o
   resto usa `public_id`; `purchased_at` local é só para exibição, o servidor
   decide o valor.
5. **Fechada para `add_items` (#39).** Nome vazio após normalização é
   `invalid_name`, como na PWA. Não foi introduzido limite de comprimento
   exclusivo do sync; o cliente deve preservar a mesma regra de presença.
6. **Aberta** (#45): a versão 1 do banco é a fundação da lista/fila, não o
   armazenamento completo do snapshot. Antes de ingerir o documento em #45,
   estender o schema com os campos/coleções necessários (incluindo
   `normalized_name`, `updated_at` e ordem estável do servidor), com migração e
   testes. O snapshot usa microssegundos; os campos locais atuais são
   milissegundos. Preservar a precisão necessária aos desempates do histórico em
   #54, sem presumir equivalência entre eles. Novo: guardar também os aliases
   devolvidos por `add_items` (`public_id` → `canonical_public_id`), para que
   comandos ainda na fila e telas encontrem o item canônico.

7. **Fechada no servidor (#39); adaptação local no segundo corte da #45.** Reativar
   leva `revives.public_id` e a versão observada (guardada na intenção) ou herdada
   do comando anterior; a entrada leva um UUID novo de reivindicação. A conversão
   de `payload.names` em `payload.items` é feita por `WirePreparer` (ver
   "Preparação do envio"); os casos que não podem ser enviados fielmente são
   recusados com motivo.
8. **Decisão mantida.** A categoria local não vai no fio; o servidor categoriza
   de novo e continua sendo a autoridade.
9. **Aberta (#45).** Guardar `normalized_name` confirmado e a precisão/ordem
   necessárias ao desempate de compras mais recentes, sem confundir o horário
   local em milissegundos com o valor confirmado em microssegundos.

## Roteiro restante da #45

Os cortes já feitos (PRs com `Refs #45`) entregam só a fundação durável e a preparação acima. A issue
continua aberta; próximos PRs pequenos, cada um sobre a `main` já integrada:

1. ~~Conversão da intenção em documento~~ — feita no segundo corte (ver
   "Preparação do envio"). Resta, para os cortes seguintes, resolver as recusas
   da tabela acima: a fila legada já tentada (`attempts > 0`, sem bytes
   guardados) e os revives/marcações sem versão recuperável pedem snapshot e
   reemissão com UUIDs novos, como prevê o contrato; `ENVELOPE_TOO_LARGE` pede a
   divisão explícita (#53).
2. Transporte — **terceiro corte feito, só a tentativa única** (ver
   [android-command-transport.md](android-command-transport.md)): um comando
   preparado/congelado por chamada, com a tentativa contada antes da rede,
   falhas classificadas e recibo tipado e validado. Falta a ordenação da fila
   inteira, lotes e a orquestração (quem chama `attempt` e quando).
3. Resultados e aliases: remover comandos confirmados, gravar aliases
   (`public_id` → `canonical_public_id`), `conflict`/`rejected`/`blocked` sem
   perder intenção, bloqueio só dos dependentes.
4. Snapshot e reconciliação: transação única, ignorar snapshot antigo, ingestão
   dos campos extras (precisão em microssegundos, `normalized_name`), reaplicar a
   fila sobre o confirmado, sem ressuscitar registros removidos/arquivados.
5. Ciclo de vida: sincronizar ao abrir/retomar com rede.
6. Validação: Rails real em teste, falha após commit e reenvio, e dois aparelhos
   antes de qualquer promessa de convergência.
