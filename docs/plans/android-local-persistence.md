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
- A versão atual é **2**. `MIGRATION_1_2` (issue #42) é aditiva: cria
  `catalog_items` (índice único em `normalized_name`) e `catalog_state`, e
  acrescenta `command_subjects.category_public_id` (nulo para o que já estava na
  fila). Lista, fila, UUIDs e `schemas/1.json` ficam intactos.
  `AlvaDatabaseMigrationTest` migra um v1 com linhas em todas as tabelas,
  confere cada uma depois, valida o schema contra `2.json` e reconstrói a
  projeção sobre a fila migrada.

## Testes

- JVM (`testDebugUnitTest`): `ProjectionReducerTest`, `AlvaMigrationsTest`,
  `MatchingCorpusTest`, `ItemCategorizerTest`, `AddPlannerTest`,
  `DuplicateHintTest`.
- Instrumentados (`connectedDebugAndroidTest`, job `android-instrumented` do
  CI): atomicidade (incluindo falha no enqueue revertendo a projeção, por UUID
  repetido e por trigger que recusa o INSERT), dependências e bloqueio,
  ordenação e concorrência, reabertura do arquivo, `synchronous=FULL`,
  migrações (incluindo 1→2 com dados), categorização/duplicados sobre Room
  (`LocalMatchingStoreTest`) e o corpus compartilhado pelo store real
  (`MatchingCorpusStoreTest`). Rodar localmente exige emulador/aparelho:
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

7. **Fechada no servidor (#39), adaptação local em #45.** Reativar leva
   `revives.public_id` e a versão observada ou herdada do comando anterior. O
   `payload.names` atual do Room deve ser convertido em `payload.items` antes
   do primeiro envio, preservando intenções e UUIDs da fila existente.
8. **Decisão mantida.** A categoria local não vai no fio; o servidor categoriza
   de novo e continua sendo a autoridade.
9. **Aberta (#45).** Guardar `normalized_name` confirmado e a precisão/ordem
   necessárias ao desempate de compras mais recentes, sem confundir o horário
   local em milissegundos com o valor confirmado em microssegundos.
