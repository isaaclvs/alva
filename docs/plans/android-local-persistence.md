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
| Estado confirmado | `sync_state`, `categories`, `confirmed_items` | Só a reconciliação (#45), nunca uma ação do usuário |
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

Fora de escopo aqui: categorização/duplicados (#42; o item entra sem
categoria e sem dedupe local), telas (#43), pareamento (#44), envio e
reconciliação (#45), widget (#56).

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
- A versão atual é 1; ainda não há migração real entre versões. Os testes
  provam a mecânica (schema exportado, `MigrationTestHelper`, abertura de um v1
  populado, falha sem destruir dados quando falta migração).

## Testes

- JVM (`testDebugUnitTest`): `ProjectionReducerTest`, `AlvaMigrationsTest`.
- Instrumentados (`connectedDebugAndroidTest`, job `android-instrumented` do
  CI): atomicidade (incluindo falha no enqueue revertendo a projeção, por UUID
  repetido e por trigger que recusa o INSERT), dependências e bloqueio,
  ordenação e concorrência, reabertura do arquivo, `synchronous=FULL` e
  migrações. Rodar localmente exige emulador/aparelho:
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
   muda e um `lock_version` enviado junto é ignorado. Primeiro passo sobre item
   conhecido de um snapshot leva o `lock_version` do snapshot. Atenção ao
   `depends_on`: precisa ser o último comando da fila **sobre o mesmo item** (é o
   que o Room já faz); um que só tocou outros itens não empresta versão.
   Para trazer de volta um item comprado digitando o nome de novo, o item de
   `add_items` leva `revives: { public_id, lock_version }` da compra vista
   localmente (ou o cliente usa `set_item_state` pending sobre o item).
4. **Aberta** (`correct_item`, #47/#48): usa `category_id` (inteiro) enquanto o
   resto usa `public_id`; `purchased_at` local é só para exibição, o servidor
   decide o valor.
5. **Fechada para `add_items` (#39).** Nome vazio ou com mais de 120 caracteres
   é `invalid_name` (a PWA continua só exigindo presença). Validar no cliente com
   o mesmo limite, para não enfileirar o que será rejeitado.
6. **Aberta** (#45): a versão 1 do banco é a fundação da lista/fila, não o
   armazenamento completo do snapshot. Antes de ingerir o documento em #45,
   estender o schema com os campos/coleções necessários (incluindo
   `normalized_name`, `updated_at` e ordem estável do servidor), com migração e
   testes. O snapshot usa microssegundos; os campos locais atuais são
   milissegundos. Preservar a precisão necessária aos desempates do histórico em
   #54, sem presumir equivalência entre eles. Novo: guardar também os aliases
   devolvidos por `add_items` (`public_id` → `canonical_public_id`), para que
   comandos ainda na fila e telas encontrem o item canônico.
