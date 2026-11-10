# Contrato de sincronização v1 (proposta)

Data: 10/11/2026. Status: **parcialmente implementado** — a direção
arquitetural e a escolha de Kotlin estão aprovadas
([ADR 0001](../adr/0001-android-offline-com-rails.md)). Pareamento, escopos e a
barreira de autenticação da `/api/v1` foram implementados na issue #35;
identidade pública, revisões e tombstones foram implementados na issue #36
(migrações `AddPublicIds` e `AddSyncRevisions`); a persistência idempotente de
comandos (ledger, linhagem do aparelho, ordem e retenção) foi implementada na
issue #38 (migração `CreateSyncCommands`). O snapshot está implementado na
issue #37. O endpoint de comandos, os executores `add_items` e
`set_item_state` e os aliases foram implementados na issue #39 (migração
`CreateSyncAliases`); os demais comandos e o cliente seguem propostos. O contrato dá um alvo versionado às tasks
do GitHub
([Alva nativo offline — #33](https://github.com/isaaclvs/alva/issues/33)), que
podem refiná-lo com evidência de implementação.

Este documento registra **como** o cliente Kotlin e o Rails conversam: comandos,
snapshots, IDs, revisões, erros, dependências, deduplicação e autenticação. As
regras de negócio (o que cada comando faz) continuam em
[android-offline.md](android-offline.md); a cobertura funcional alvo está em
[android-parity-inventory.md](android-parity-inventory.md).

## Escopo e versão

- V1 cobre uma casa e dois aparelhos, com o produto atual preservado: adicionar,
  marcar/desmarcar, corrigir, remover, desfazer, finalizar compras, habituais,
  comprar de novo, busca e modo compras.
- Transporte HTTPS/JSON. A versão vai na rota (`/api/v1/...`); mudanças
  incompatíveis exigem `/api/v2`, nunca reinterpretar um campo de v1.
- SQLite continua sendo o banco do servidor. O servidor decide a ordem
  compartilhada; o relógio do aparelho nunca decide quem vence.
- Sem CRDT, event sourcing ou backend novo. A fila local é uma fila de
  operações, não um log de eventos.

## Autenticação e autorização

Implementado na issue #35 para pareamento, escopos e a barreira de acesso da
`/api/v1`; o snapshot está implementado na issue #37, o ledger e o processador na
#38 e `POST /api/v1/commands` (com `add_items` e `set_item_state`) na #39.

- Pareamento explícito de sync, online, com a senha compartilhada que já existe
  (`HouseholdAccess`): `POST /api/v1/devices` devolve `device_id` e o segredo em
  HTTP 201 com `Cache-Control: no-store`, somente no corpo dessa resposta. O
  segredo é guardado só como digest no servidor e no aparelho sob Android
  Keystore; a senha nunca é embutida no APK. Cookie da PWA e token de aparelho
  não substituem a senha, e escopo enviado pelo cliente é ignorado.
- O pareamento antigo do widget (`POST /api/android/devices`) continua emitindo
  apenas `add_only`.
- Toda requisição de sync usa `Authorization: Bearer <segredo>`, com parsing
  estrito (esquema `Bearer ` exato, sem espaços extras). A identidade e a casa
  vêm só do token; `household_id`, `device_id` e `scope` do payload nunca
  selecionam a casa nem elevam permissões.
- Escopos: `add_only` (credencial do widget, limitada à inclusão, sem ampliação
  silenciosa) e `sync` (leitura de snapshot e todos os comandos v1).
  `quick_add` aceita os dois. Credencial `add_only` na base protegida responde
  403 `forbidden_scope`; ausente, malformada, desconhecida, revogada ou anterior
  à rotação responde 401 `unauthorized`, sem alterar dados.
- Sem senha configurada, o pareamento de sync responde 403 `sync_unavailable`.
  O modo privado sem senha da PWA continua funcionando, e credenciais antigas
  passam a ser recusadas com 401 em vez de erro 500.
- Revogação individual por `revoked_at`, idempotente e sem afetar o outro
  aparelho. Rotacionar a senha da casa invalida todas as credenciais pelo digest
  atual, sem apagar dados locais nem a fila; uma nova emissão já funciona com o
  novo digest. Reconectar à mesma casa preserva ações pendentes.
- Rate limit de pareamento: 5 tentativas por 15 minutos por IP, compartilhado
  entre `/api/android/devices` e `/api/v1/devices`, com 429 `rate_limited` e
  `Retry-After`. O contador fica em memória por processo; no Atlas há um único
  processo Puma e reiniciar o serviço zera a contagem. O IP segue as regras de
  proxy confiável do Rails (`request.remote_ip`).
- Toda resposta de `/api/v1` usa `Cache-Control: no-store`.
- Primeira carga exige rede. Depois a cópia local funciona offline. Não
  restaurar credenciais cegamente por backup Android: reinstalar exige
  reautorização e nova carga. O lado do aparelho (cofre no Keystore, regras de
  backup, reconexão com `previous_token` e vínculo da casa) está implementado na
  variante debug e descrito em [android-house-access.md](android-house-access.md).

### Emissão, revogação e rotação

Emissão (nova credencial `sync`, mostrada uma vez):

```bash
curl -sS -X POST https://<host>/api/v1/devices \
  -H 'Content-Type: application/json' \
  -d '{"password":"<senha da casa>","name":"Celular da Ana"}'
```

Reautorização do mesmo aparelho (implementada na #38): o aparelho envia, além
da senha, o segredo da credencial que tinha antes como `previous_token`. É a
única prova de continuidade; `device_id`, `lineage_id` ou nome enviados pelo
cliente não concedem nada. O novo registro `AndroidDevice` herda a **linhagem**
(`lineage_id`) do anterior e passa a poder resolver os comandos que ele enviou
(ver "Idempotência e deduplicação"). O segredo anterior vale mesmo revogado ou
depois da rotação da senha — a senha nova continua sendo exigida —, mas só se
for de uma credencial `sync` desta casa; senão o pareamento responde 422
`invalid_previous_token` sem criar aparelho. O parâmetro é filtrado dos logs
como os demais segredos.

Revogação individual, no Rails console do servidor:

```ruby
AndroidDevice.where(revoked_at: nil).pluck(:id, :name, :scope)
AndroidDevice.find(12).revoke!   # repetir é seguro
```

Rotação da senha: gere um novo hash bcrypt, atualize `ALVA_PASSWORD_DIGEST` e
reinicie o serviço (ver [tailscale-funnel.md](../deployment/tailscale-funnel.md)).
Todos os aparelhos precisam parear de novo. Não há painel administrativo nesta
versão.

## Envelope de comando

`POST /api/v1/commands` (implementado na #39), `Authorization: Bearer <segredo>`
de uma credencial `sync`, corpo JSON, sem sufixo de formato. Um lote de comandos
de um mesmo aparelho, em ordem (exemplo real: adicionar offline → marcar →
desmarcar, ver "Comandos v1"):

```json
{
  "device_id": 12,
  "observed_revision": 840,
  "commands": [
    {
      "id": "5f0c2f0e-0000-4000-8000-000000000001",
      "type": "add_items",
      "depends_on": null,
      "payload": { "items": [ { "public_id": "9a1d0000-0000-4000-8000-000000000001", "name": "Leite" } ] }
    }
  ]
}
```

Resposta (HTTP 200 para qualquer lote bem formado; `Cache-Control: no-store`):

```json
{
  "revision": 841,
  "results": [
    {
      "id": "5f0c2f0e-0000-4000-8000-000000000001",
      "status": "applied",
      "revision": 841,
      "data": { "items": [ { "public_id": "9a1d0000-0000-4000-8000-000000000001",
                             "canonical_public_id": "9a1d0000-0000-4000-8000-000000000001",
                             "name": "Leite", "status": "pending", "purchased_at": null,
                             "lock_version": 0, "outcome": "created" } ] }
    }
  ],
  "snapshot_required": false
}
```

Regras do envelope:

- A identidade do aparelho vem do `Authorization`; `device_id` é apenas eco
  para auditoria e pode ser omitido (o servidor não depende dele para autorizar).
- `id` é o UUID gerado no aparelho e a chave de idempotência do comando.
- `depends_on` referencia o comando anterior do mesmo aparelho do qual este
  depende causalmente (normalmente o comando imediatamente anterior; `null` se
  independente).
- `observed_revision` é a revisão da casa que o aparelho conhecia. Entra no
  digest do comando e fica à disposição de comandos que a usem; `add_items` e
  `set_item_state` **não** a usam como precondição (qualquer mudança da casa a
  invalidaria, inclusive as do próprio lote). A precondição deles é o
  `lock_version` do item, explícito ou herdado (ver "Comandos v1").
- `preconditions` é específico do comando (ex.: `lock_version` do item).
- `status` de cada resultado: `applied`, `duplicate` (mesmo UUID já processado),
  `noop` (já estava no estado desejado), `conflict` (precondição falhou),
  `rejected` (erro definitivo) ou `blocked` (dependente de comando não aplicado).
  O resultado de `duplicate` leva `original_status` (`applied` ou `noop`),
  `revision` e `data` do resultado original; `conflict`, `rejected` e `blocked`
  levam `error` (código da tabela de erros ou `dependency_not_applied`).
- A ordem dos `results` é a ordem dos comandos enviados.
- `revision` do corpo é a revisão da casa depois do lote. `snapshot_required` é
  `true` quando algum resultado mostra que a visão do aparelho está velha: um
  `conflict`, ou uma rejeição `gone` ou `not_found`. O cliente então baixa um
  snapshot antes de decidir o que reemitir; o fim do lote não depende disso.
- Limites do envelope: de 1 a 50 comandos por requisição e `observed_revision`
  inteiro ≥ 0 (ou ausente); fora disso a requisição inteira é `422
  malformed_command`, sem efeito (assim como JSON inválido). Um comando mal
  formado dentro de um lote bom falha sozinho, como `rejected`.
- Um lote bem formado responde com os resultados por comando; os códigos HTTP
  da tabela de erros valem para a requisição inteira (autenticação, envelope
  malformado, limite, erro interno). Um erro interno no meio do lote não perde
  o que já foi confirmado: o reenvio do lote devolve `duplicate` para esses.

## Comandos v1

`add_items` e `set_item_state` estão implementados (#39); os demais são o alvo
das tasks seguintes. As lacunas de UUIDs e versões que a base Room registrou em
[android-local-persistence.md](android-local-persistence.md#lacunas-observadas-no-contrato-v1-para-37383945)
estão fechadas abaixo: o comando leva os UUIDs dos itens criados offline, um item
ainda não visto é alcançado por `depends_on`, e a versão de cada passo da fila é
herdada do resultado do passo anterior no servidor, sem reescrever o conteúdo de
um comando já enviado.

| `type` | `payload` | `preconditions` | Equivalente atual |
| --- | --- | --- | --- |
| `add_items` | `items: [{ public_id, name, revives? }]` (já separados no aparelho, de 1 a 30) | `revives` por item, só para trazer de volta uma compra | `Household#add_items` (`#add_item_for_sync`) |
| `set_item_state` | `public_id`, `desired: "pending"\|"purchased"` | `lock_version` (herdado de `depends_on` quando este tocou o item) | `ListItem#toggle_purchased!` (estado desejado, nunca toggle) |
| `undo_item_state` | `public_id`, `status`, `purchased_at` | `lock_version` | `PurchaseUndo` / `#undo_purchase!` |
| `correct_item` | `public_id`, `name`, `category_id?` | `lock_version` | `ListItem#correct` + `ItemCategorizer.learn` |
| `remove_item` | `public_id` | `lock_version` | `ListItemSnapshot` + `destroy` |
| `restore_item` | campos do item removido (nome, categoria, `purchased_at`, `created_at`, `position`) | item não existe / não há pendente homônimo | `Household#restore_item` |
| `finish_shopping` | `items: [{ public_id, lock_version }]` — só os comprados vistos neste aparelho | cada `lock_version` | `Household#finish_shopping!` |
| `undo_shopping` | `trip_public_id` | viagem é a mais recente | `Household#undo_shopping!` |
| `create_habitual` | `name` | — | `HabitualItemsController#create` |
| `remove_habitual` | `public_id` | — | `HabitualItemsController#destroy` |
| `add_habituals` | `public_ids: []` ou `all: true` | — | `#add_selected` / `#add_all` |

### `add_items`

```json
{
  "id": "5f0c2f0e-0000-4000-8000-000000000001",
  "type": "add_items",
  "depends_on": null,
  "payload": {
    "items": [
      { "public_id": "9a1d0000-0000-4000-8000-000000000001", "name": "Tapioca" },
      { "public_id": "9a1d0000-0000-4000-8000-000000000002", "name": "Detergente",
        "revives": { "public_id": "40000000-0000-4000-8000-000000000002", "lock_version": 0 } }
    ]
  }
}
```

- `items` substitui o antigo `names`: cada nome viaja com o `public_id` (UUID em
  qualquer caixa, normalizado para minúsculas) que o aparelho
  gerou, na ordem de `payload.items`. O servidor cria a linha **com esse
  mesmo UUID**; ele nunca é reescrito. De 1 a 30 itens, sem UUID repetido no
  comando. O nome é normalizado com `squish` e não pode ficar vazio
  (`invalid_name`, com `index` do item); não há limite de comprimento exclusivo
  do sync. A categoria e a
  duplicidade seguem as regras do Rails (`ItemCategorizer`, `TextNormalizer`,
  `Household#add_item_for_sync`), como se o nome fosse digitado na PWA.
- Resultado por item, em `data.items` (mesma ordem), com `outcome`:
  - `created`: item novo, pendente, com o UUID enviado.
  - `duplicate`: o nome já estava pendente na lista (ou o UUID já era deste
    item). Nenhuma linha nova; o UUID enviado passa a ser **alias** do item
    canônico (`canonical_public_id`).
  - `revived`: o nome era de um item comprado e voltou a pendente; o UUID enviado
    vira alias dele. Só acontece com `revives` (abaixo).
- Cada entrada devolve `public_id` (o que o aparelho citou), `canonical_public_id`
  (o registro do servidor; **diferente de `public_id` = é um alias**), `name`
  (o do registro canônico), `status`, `purchased_at` e `lock_version` do item
  **depois** do comando. É esse `lock_version` que o próximo comando da fila
  herda.
- `applied` se algo entrou na lista (`created`/`revived`, uma revisão para o
  comando todo); `noop` se tudo já estava lá (só aliases, nenhuma revisão).
- O comando é tudo ou nada. Qualquer item em conflito ou rejeitado desfaz o
  comando inteiro (nada fica gravado, nem aliases) e o resultado traz `index` e
  `public_id` do item que parou o lote.
- **Comprado não volta sozinho.** Se o nome casa com um item *comprado*, o
  servidor só o traz de volta quando o item diz qual compra o aparelho viu:
  `revives: { public_id, lock_version }`. O item tem de estar na lista, comprado,
  com aquele `lock_version`. Se `depends_on` devolveu uma versão desse mesmo
  item, ela também vale para a reativação: usar a maior versão entre o recibo
  e a explícita. Nesse caso a versão explícita pode ser omitida, permitindo
  adicionar → marcar → adicionar de novo sem inventar versões no aparelho.
  Sem versão explícita nem recibo relevante, é `rejected` / `malformed_command`.
  Uma versão diferente da atual é `conflict` / `precondition_failed` com
  `data.reason` `purchase_not_observed` (sem `revives`) ou `purchase_changed`
  (`revives` velho) e `data.current` com o estado atual. `revives` apontando
  para item arquivado/removido é `rejected` / `gone`, e para UUID desconhecido,
  `rejected` / `not_found`. Assim um pedido antigo não reabre uma compra feita
  há pouco; o aparelho mostra o estado atual e decide.
- **UUID já usado.** Um UUID de item desta casa que já identifica um item
  vivo com o mesmo nome (ou um alias dele) responde `duplicate`, sem efeito. Com
  outro nome, ou se pertence a outra casa — item, alias ou tombstone —, é
  `rejected` / `public_id_conflict`; a resposta é a mesma nos dois casos e não
  diz de quem é o UUID. UUID de item removido ou arquivado desta casa é
  `rejected` / `gone` (`data.reason`: `removed` ou `archived`); só um novo
  `add_items` com **outro** UUID (ou `restore_item`) coloca o nome de volta.

Dois aparelhos que adicionam o mesmo nome: o primeiro comando cria o item, o
segundo recebe `noop` com `duplicate` e o UUID dele como alias. Exemplo real do
segundo aparelho (item `…02` unificado com o canônico `…01`, que já estava na
versão 2):

```json
{
  "id": "5f0c2f0e-0000-4000-8000-000000000004",
  "status": "noop",
  "revision": 843,
  "data": { "items": [ {
    "public_id": "9a1d0000-0000-4000-8000-000000000002",
    "canonical_public_id": "9a1d0000-0000-4000-8000-000000000001",
    "name": "Tapioca", "status": "pending", "purchased_at": null,
    "lock_version": 2, "outcome": "duplicate" } ] }
}
```

### `set_item_state`

```json
{
  "id": "5f0c2f0e-0000-4000-8000-000000000002",
  "type": "set_item_state",
  "depends_on": "5f0c2f0e-0000-4000-8000-000000000001",
  "preconditions": {},
  "payload": { "public_id": "9a1d0000-0000-4000-8000-000000000001", "desired": "purchased" }
}
```

- `desired` é o **estado desejado**, nunca toggle: `purchased` ou `pending`. O
  item já estar nesse estado é `noop` (nem muda `purchased_at` nem a revisão),
  também quando a versão enviada está velha: o resultado que o aparelho queria
  já existe. Reenviar o mesmo UUID devolve `duplicate`. `purchased_at` é a hora
  do servidor.
- `public_id` é o UUID do item **ou um alias** dele (resolvido na casa da
  credencial). Item arquivado ou removido responde `rejected` / `gone`
  (`data.reason`, `canonical_public_id`); UUID inexistente ou de outra casa,
  `rejected` / `not_found`, com resposta idêntica; nada é recriado.
- **Versão esperada (`lock_version`):**
  1. Se o comando em `depends_on` mexeu neste item (o resultado dele, no ledger,
     tem uma entrada para o item canônico), a versão **herdada** é o
     `lock_version` que ele deixou. O servidor lê isso do resultado do passo
     anterior; o conteúdo do comando nunca é reescrito. Se também houver
     `preconditions.lock_version`, usar a **maior** das duas versões: um
     snapshot mais recente pode ter chegado enquanto o recibo anterior ainda
     aguardava confirmação. Uma versão explícita inválida é malformada mesmo
     quando há versão herdada; uma versão acima da atual gera conflito.
  2. Senão, `preconditions.lock_version` (inteiro ≥ 0) é obrigatório: a versão que
     o aparelho viu no último snapshot. Ausente ou inválido é `rejected` /
     `malformed_command` (`data.field`: `preconditions.lock_version`). Um
     `depends_on` que tocou *outros* itens não empresta versão.
  Versão diferente da atual é `conflict` / `precondition_failed`, com
  `data.reason` `version_changed`, `data.expected_lock_version` e `data.current`
  (estado atual do item); nada é sobrescrito.
- Desmarcar um item quando já há outro pendente com o mesmo nome (a mesma regra
  da caixinha da PWA) é `conflict` com `data.reason` `duplicate_pending`.
- Efetivo: `applied`, uma revisão, e o resultado traz `data.item` com o estado e o
  `lock_version` depois do comando.

### Fila causal: adicionar → marcar → desmarcar

O aparelho gera o UUID do item e três UUIDs de comando e envia, sem nenhuma
versão (ele não as conhece), cada passo dependendo do anterior:

```json
{
  "observed_revision": 840,
  "commands": [
    { "id": "5f0c2f0e-0000-4000-8000-000000000001", "type": "add_items", "depends_on": null,
      "payload": { "items": [ { "public_id": "9a1d0000-0000-4000-8000-000000000001", "name": "Tapioca" } ] } },
    { "id": "5f0c2f0e-0000-4000-8000-000000000002", "type": "set_item_state",
      "depends_on": "5f0c2f0e-0000-4000-8000-000000000001",
      "payload": { "public_id": "9a1d0000-0000-4000-8000-000000000001", "desired": "purchased" } },
    { "id": "5f0c2f0e-0000-4000-8000-000000000003", "type": "set_item_state",
      "depends_on": "5f0c2f0e-0000-4000-8000-000000000002",
      "payload": { "public_id": "9a1d0000-0000-4000-8000-000000000001", "desired": "pending" } }
  ]
}
```

Resposta (resumida aos campos que importam): três `applied`, com revisões
841, 842 e 843 e `lock_version` do item 0 → 1 → 2. O segundo passo esperou a
versão 0 que o primeiro deixou; o terceiro, a versão 1 do segundo.

```json
{ "revision": 843, "snapshot_required": false,
  "results": [
    { "id": "…0001", "status": "applied", "revision": 841,
      "data": { "items": [ { "public_id": "9a1d…0001", "canonical_public_id": "9a1d…0001", "name": "Tapioca",
                             "status": "pending", "purchased_at": null, "lock_version": 0, "outcome": "created" } ] } },
    { "id": "…0002", "status": "applied", "revision": 842,
      "data": { "item": { "public_id": "9a1d…0001", "canonical_public_id": "9a1d…0001", "name": "Tapioca",
                          "status": "purchased", "purchased_at": "2026-11-10T12:00:00.000000Z", "lock_version": 1 } } },
    { "id": "…0003", "status": "applied", "revision": 843,
      "data": { "item": { "public_id": "9a1d…0001", "canonical_public_id": "9a1d…0001", "name": "Tapioca",
                          "status": "pending", "purchased_at": null, "lock_version": 2 } } } ] }
```

- Reenviar o lote inteiro depois de perder a resposta devolve três `duplicate`
  (com `original_status` e os mesmos `data`) e o item continua pendente na versão 2:
  nada é aplicado outra vez e o estado desejado não é invertido.
- A herança vale entre lotes e entre credenciais da mesma linhagem: o resultado
  vem do ledger. Se o passo `depends_on` ainda não foi registrado, o dependente é
  `blocked` (`dependency_not_applied`) e se aplica quando o outro entrar.
- Se outro aparelho mexeu no item no meio (a versão atual não é a que o passo
  anterior deixou), o passo seguinte é `conflict` e os que dependem dele ficam
  `blocked`: a fila nunca sobrescreve uma versão que o aparelho não viu.
- Quando o primeiro passo da fila é sobre um item que o aparelho já conhecia (de
  um snapshot), ele leva `preconditions.lock_version` explícito e os seguintes
  continuam herdando.
- Alias na mesma fila: se o `add_items` foi unificado (`canonical_public_id`
  diferente), os passos seguintes continuam citando o UUID do aparelho e herdam a
  versão do item canônico, que é o que o resultado do primeiro passo registra.

Observações:

- Categorização automática e detecção de duplicados são feitas **localmente**
  com a mesma normalização do Rails (`TextNormalizer`); o servidor é a
  autoridade final e resolve divergências sem perder o nome digitado.
- `correct_item` com categoria escolhida à mão grava aprendizado no dicionário
  (`learned: true`) como hoje; o aprendizado concorrente usa precondição da
  revisão do dicionário.
- `finish_shopping` nunca arquiva "todos os comprados que existirem": envia
  explicitamente os itens vistos e suas versões.
- Comandos que a PWA já faz em lote (`update_all`) também incrementam a revisão
  (ver abaixo); o Android não depende de callbacks por item.

## Idempotência e deduplicação

Implementado na issue #38 (`SyncCommand`, `SyncCommandProcessor`) e ligado ao
endpoint `POST /api/v1/commands` na #39. Os comandos de negócio registram seu
executor em `SyncCommandProcessor::EXECUTORS` nas tasks que os introduzem; hoje
são `add_items` e `set_item_state` (`app/services/sync_commands/`). Um tipo ainda
não implementado responde `rejected` / `unsupported_command`.

### O que é gravado, e quando

O servidor executa cada comando numa transação própria, sob o lock da casa
(`Household#with_list_lock`), e grava o registro idempotente `sync_commands` **na
mesma transação do efeito**. Ou os dois são confirmados, ou nenhum. O registro
guarda: casa, UUID do comando (`(household_id, command_id)` é único no banco),
credencial de origem (`android_device_id`), linhagem do aparelho, tipo,
`depends_on`, digest SHA-256 do conteúdo canônico, status (`applied`/`noop`),
revisão da casa após o efeito e o resultado (`data`).

Só **consomem** o UUID os resultados que realmente terminaram: `applied` e
`noop`. `conflict`, `rejected`, `blocked`, `unsupported_command`,
`malformed_command` e qualquer exceção revertem a transação e não deixam
registro — o comando não foi consumido e pode ser reavaliado. Um `noop` é
registrado porque reenviá-lo não pode executar depois o que a primeira
avaliação deliberadamente não fez (estado que mudou entre as tentativas).

| Resultado da primeira execução | Registro | Reenvio do mesmo `id` |
| --- | --- | --- |
| `applied`, `noop` | sim, com o efeito | `duplicate` com `original_status`, `revision` e `data` originais |
| `conflict`, `rejected` (regra de negócio) | não | reavaliado; pode ser reenviado com conteúdo corrigido |
| `blocked` (dependência sem registro) | não | aplicado assim que a dependência estiver registrada |
| `malformed_command`, `unsupported_command` | não | reavaliado |
| exceção (`internal_error`, banco ocupado) | não; tudo revertido | reavaliado, sem efeito pela metade |

### Conteúdo canônico

O digest cobre `type`, `depends_on`, `observed_revision` do próprio comando,
`preconditions` e `payload`, com chaves ordenadas recursivamente (a ordem das
chaves e símbolos versus strings não importa; a ordem de arrays importa). O UUID
é a chave, não o conteúdo. Campos fora do contrato são ignorados: nem entram no
digest nem chegam ao executor. O `observed_revision` do envelope é só o padrão
entregue ao executor; não entra no digest, para que reenviar o comando em outro
lote continue sendo um reenvio.

### Reenvio, conflito e concorrência

- Mesmo UUID, mesma casa, mesma linhagem e mesmo conteúdo: `duplicate`, sem
  segundo efeito, mesmo se o cliente perdeu a resposta depois do commit.
- Mesmo UUID com conteúdo diferente, ou de outra linhagem: `rejected` com
  `idempotency_conflict`. O erro é o mesmo nos dois casos e não revela o
  resultado original nem de quem ele é.
- Dois envios simultâneos do mesmo UUID (conexões independentes) são
  serializados pelo lock da casa: um aplica e o outro lê o registro e devolve
  `duplicate`. Se o primeiro falhar e reverter, o segundo aplica. O índice único
  do banco é a última barreira; a consulta ao registro ocorre dentro do lock.
- UUIDs de casas diferentes são independentes.

### Linhagem do aparelho e reautorização

Reautorizar cria um novo `AndroidDevice` (novo id e segredo), então a identidade
de um comando não pode ser só o id da credencial. A **linhagem**
(`android_devices.lineage_id`, UUID gerado pelo servidor) acompanha o aparelho
físico: nasce com a primeira credencial e é herdada apenas quando o pareamento
traz o `previous_token` correspondente (ver "Autenticação e autorização").

- A casa e a linhagem vêm sempre da credencial autenticada; nada do payload
  (`household_id`, `device_id`, `lineage_id`) escolhe uma ou outra.
- Uma credencial da mesma linhagem, inclusive a que reautorizou depois de
  revogação ou rotação de senha, reenvia o comando antigo e recebe o resultado
  original, sem duplicar o efeito. O registro continua apontando para a
  credencial que o executou, como auditoria.
- Outro aparelho — mesmo pareado com a senha da casa — tem outra linhagem: não
  consegue reenviar, herdar nem tornar suas as operações alheias, nem
  referenciá-las em `depends_on`.
- Limite assumido: se o aparelho perdeu o segredo anterior (Keystore apagado,
  reinstalação com a fila preservada), ele só consegue uma linhagem nova. Os
  comandos antigos dele respondem `idempotency_conflict`; o cliente deve
  sincronizar um snapshot, ver o que de fato foi aplicado e reemitir o que
  faltar com UUIDs novos. Isso é uma decisão do cliente, não do servidor.

### Ordem e dependências

- Dentro de um lote os comandos são tentados na ordem enviada, cada um na sua
  transação; um lote não é atômico. Entre lotes, a ordem de chegada é a do lock
  da casa; a causalidade vem de `depends_on`, não da ordem de chegada.
- `depends_on` só é satisfeito por um comando **registrado** da **mesma
  linhagem** (aplicado ou `noop`). Dependência ainda não enviada, posterior no
  mesmo lote, com conflito/rejeição ou de outra linhagem deixa o dependente
  `blocked` (`dependency_not_applied`), sem efeito e sem registro. Quando a
  dependência for registrada, o reenvio do dependente é aplicado.
- Como o estado de um dependente deriva do registro, a decisão sobrevive a
  respostas perdidas e a reautorização. `depends_on` igual ao próprio `id` é
  `malformed_command`.
- Comandos independentes (`depends_on: null`) não são bloqueados por falhas dos
  outros.

### Retenção

Os registros **não expiram e não são podados**: nenhum prazo, nenhuma rotina de
limpeza e nenhuma remoção ao revogar uma credencial. A fila do aparelho pode
manter um comando por tempo ilimitado offline, e o servidor não sabe o que a
fila ainda guarda; apagar o registro permitiria que uma compra fosse duplicada
semanas depois. Os aparelhos (`AndroidDevice`) também nunca são apagados, só
revogados, para que a linhagem continue resolvível. O custo é desprezível para
uma casa e dois aparelhos (algumas centenas de bytes por comando). Podar só
seria seguro com confirmação explícita do cliente (por exemplo, "já recebi o
resultado de tudo até este UUID"), que seria uma mudança do contrato e não está
em v1. Tombstones e aliases seguem o mesmo critério.

### Para quem escreve um executor

Um executor (`call(command)` devolvendo `SyncCommandProcessor::Outcome`) roda
dentro da transação e do lock da casa: deve registrar a mudança com
`Household#record_shared_change!` quando houver efeito, não capturar erros de
banco e não escrever fora da transação. Broadcast (Turbo/ActionCable) precisa
acontecer **depois** do commit, nunca dentro do executor — um rollback posterior
não pode ter anunciado algo que não existiu.

- O processador envolve cada comando em `Household#batch_list_broadcasts`, que
  engloba a transação: a lista é transmitida **uma vez**, depois do commit, e só
  se algo foi confirmado. Conflito, rejeição, exceção ou qualquer rollback não
  chegam ao `after_commit` e não anunciam nada.
- Métodos do `Household` que transmitem explicitamente (`finish_shopping!`,
  `undo_shopping!`) passam por `broadcast_list_after_commit`
  (`ActiveRecord.after_all_transactions_commit`): sem transação aberta o
  comportamento é o de sempre; dentro da transação do processador, a mensagem
  espera o commit e é descartada num rollback. Executor novo que precise
  transmitir deve usar esse método, nunca `broadcast_list`.
- `command.dependency` é a linha do ledger (`SyncCommand`) do comando em
  `depends_on`, já verificada como aplicada e da mesma linhagem (ou `nil`). É só
  leitura: o executor lê dali o que o passo anterior produziu (UUIDs canônicos,
  versões) — como o `set_item_state` herda o `lock_version` — e o conteúdo do
  comando atual nunca é alterado.

## IDs públicos e aliases

- Os IDs inteiros do Rails continuam válidos internamente; o contrato expõe um
  `public_id` (UUID) por registro. **Implementado (#36):** casas, categorias,
  dicionário, itens (arquivados inclusive), habituais e compras têm `public_id`
  único no banco. A migração preenche linhas existentes sem tocar em ids,
  relacionamentos ou atributos, e o desfazer remoção repõe a mesma identidade
  (snapshots assinados antes de #36 ganham um UUID novo ao voltar).
- Item criado offline já nasce com `public_id` gerado no aparelho; o servidor
  cria a linha com esse mesmo `public_id`, para que comandos posteriores o
  alcancem. O valor já enviado nunca é sobrescrito. **Implementado (#39):** o
  UUID viaja em `add_items.payload.items[].public_id`.
- Quando dois aparelhos adicionam o mesmo nome, o servidor mantém **um** item
  canônico. O `public_id` perdedor vira alias do canônico
  (`sync_aliases`: `household_id`, `public_id`, `canonical_public_id`,
  `command_id` de origem), e comandos que citam o alias são resolvidos para o
  canônico. Também vira alias o UUID de um nome que reviveu um item comprado.
  **Implementado (#39).** O alias é criado na mesma transação do comando; o
  resultado de `add_items` o devolve (`canonical_public_id` diferente de
  `public_id`) e `set_item_state` o resolve.
- **Namespace.** Um UUID só pode ser reivindicado uma vez, entre todos os itens,
  aliases e tombstones de todas as casas (índices únicos globais, como nos itens).
  Toda *busca* é restrita à casa da credencial: o UUID de outra casa responde
  `not_found` (em `set_item_state`) como se não existisse, e tentar reivindicá-lo
  em `add_items` responde `public_id_conflict`, sem dizer de quem é.
- O alias aponta para o `public_id` canônico (texto, não chave estrangeira): se o
  item canônico for arquivado ou removido, o alias continua respondendo `gone`.
  Limite conhecido: o desfazer compra (#51) descarta duplicados e grava
  tombstones; aliases que apontavam para um duplicado descartado passam a
  responder `gone`, e a task que o implementa precisa decidir se os redireciona
  para o item que ficou.
- Aliases são mantidos pelo mesmo critério dos UUIDs de operação: nunca são
  podados, pois pode haver comando em trânsito que os cite.

## Revisões

Regras implementadas em #36 (`Household#record_shared_change!`):

- Cada casa tem uma revisão monotônica (`households.sync_revision`), começando
  em zero e incrementada dentro da transação que confirma a mudança. `updated_at`
  não é cursor, e relógio de aparelho nunca decide a ordem.
- Duplicata, validação rejeitada e operação sem efeito **não** incrementam.
- **Granularidade:** uma revisão por operação compartilhada efetiva. Chamadas
  internas participam da mesma operação; nenhum caminho incrementa por item.
- Cobre os caminhos que os comandos v1 espelham: adicionar/reviver, marcar e
  desmarcar, corrigir, remover/restaurar, habituais (salvar, remover,
  adicionar selecionados/todos), finalizar e desfazer compras — inclusive
  `update_all` de “Compra feita” e o `delete` do desfazer compra.
- Revisão e broadcast são responsabilidades distintas: a revisão persiste dentro
  da transação, o broadcast acontece depois da confirmação.
- Snapshots e resultados carregam a revisão. O aparelho guarda a última revisão
  confirmada.
- Um snapshot com revisão **menor** que a confirmada nunca é aplicado; uma
  leitura remota nunca apaga a fila local.
- Comandos usam `observed_revision`/`lock_version` como precondição; conflito
  bloqueia dependentes causais, não ações independentes.
- Dicionário e categorias são globais: têm revisão própria em `sync_scopes`
  (`SyncScope::DICTIONARY`), e uma mudança efetiva (aprendizado ou seed) avança
  essa revisão e as casas afetadas na mesma transação. Seed sem mudança não
  incrementa nada.

## Exclusões (tombstones)

- Remover um item ou um habitual grava um `SyncTombstone` (tipo do registro,
  `public_id`, escopo, casa e revisão da exclusão) na mesma transação do
  apagamento, com a revisão nova. O desfazer compra também grava os tombstones
  do que apaga (a viagem e os duplicados arquivados que ele descarta).
- Arquivar um item **não** é excluí-lo: a linha continua no histórico e não gera
  tombstone.
- Restauração explícita apaga o tombstone, para o mesmo UUID não ficar ativo e
  removido ao mesmo tempo.
- Tombs são mantidos enquanto um comando em trânsito puder citá-los; nenhum
  prazo arbitrário que permita duplicar uma compra depois de muito tempo
  offline.

## Snapshot

**Implementado na issue #37** (`GET /api/v1/snapshot`, `SyncSnapshot`). O
snapshot é o conjunto sincronizado **completo** de uma casa, numa única leitura
consistente; não há modo incremental na v1.

### Requisição

- `GET /api/v1/snapshot` com `Authorization: Bearer <segredo>` de uma credencial
  `sync` (ver "Autenticação e autorização"). A casa vem só da credencial:
  `household_id`, `scope` e afins enviados na requisição são ignorados.
- Sem parâmetros, sem sufixo de formato (`/snapshot.xml` é 404) e só `GET`.
  Resposta `200`, `application/json`, `Cache-Control: no-store`. O `Rack::Deflater`
  do app comprime JSON quando o cliente envia `Accept-Encoding: gzip`.
- Erros: `401 {"error":"unauthorized"}` (ausente, malformada, desconhecida,
  revogada, anterior à rotação ou sem senha configurada) e `403
  {"error":"forbidden_scope"}` (credencial `add_only` do widget). Nenhum dos dois
  lê dados. Os erros tratados do snapshot usam `{"error": "<code>"}`. Falha
  inesperada é `500 {"error":"internal_error"}` (retentável, sem efeito: a
  leitura não grava). A base protegida reporta a exceção internamente sem
  serializar seus detalhes; erros HTTP conhecidos mantêm seu status no Rails.

### Documento

```json
{
  "schema_version": 1,
  "revision": 840,
  "dictionary_revision": 12,
  "household": { "public_id": "…", "name": "Casa" },
  "categories": [ { "public_id": "…", "name": "Hortifruti", "position": 1 } ],
  "catalog_items": [ { "public_id": "…", "name": "Leite", "normalized_name": "leite",
                       "category_public_id": "…", "learned": false } ],
  "list_items": [ { "public_id": "…", "name": "Leite", "normalized_name": "leite",
                    "category_public_id": "…", "status": "pending", "position": null,
                    "purchased_at": null, "created_at": "2026-11-10T12:00:00.123456Z",
                    "updated_at": "2026-11-10T12:00:00.123456Z", "lock_version": 0 } ],
  "archived_items": [ { "public_id": "…", "…": "campos de list_items", "shopping_trip_public_id": "…" } ],
  "shopping_trips": [ { "public_id": "…", "finished_at": "…", "created_at": "…" } ],
  "habitual_items": [ { "public_id": "…", "name": "Café", "normalized_name": "cafe",
                        "created_at": "…" } ],
  "tombstones": [ { "record_type": "list_item", "public_id": "…", "revision": 838 } ]
}
```

- `revision` é `households.sync_revision` e `dictionary_revision` é a revisão do
  escopo `dictionary`; ambas descrevem **exatamente** o estado devolvido. Uma
  mudança de dicionário também move `revision` (ver "Revisões"), então comparar
  só `revision` basta para saber se algo mudou.
- Registros se referem uns aos outros por `public_id` (`category_public_id`,
  `shopping_trip_public_id`; `null` para item sem categoria). **Nenhum id inteiro
  do Rails é serializado.** Os campos são listados um a um no servidor: uma coluna
  nova nunca entra no contrato por acidente.
- `list_items` é a lista viva (`shopping_trip_id` nulo) e `archived_items` é o
  histórico (com viagem). Os dois carregam tudo o que desfazer remoção
  (`ListItemSnapshot`), desfazer marcação (`status`, `purchased_at`,
  `lock_version`) e desfazer compra precisam, para o aparelho compensar sem rede.
- Datas são UTC ISO 8601 com microssegundos (`…:00.123456Z`), como no
  `ListItemSnapshot`: itens criados no mesmo segundo mantêm a ordem.
- **A ordem dos arrays faz parte do contrato:** cada coleção vem em ordem
  crescente do id interno (categorias por `position`, depois id; tombstones por
  `revision`, depois id). É o desempate que o Rails usa (viagem mais recente,
  grafia mais recente) e o aparelho não tem outro modo de reproduzi-lo.
- Fica de fora, por decisão: segredos, digests, dispositivos e credenciais; dados
  de outra casa; preferências do aparelho (tema, tela, modo compras); resultados
  de comandos e aliases. Campos novos só entram de forma **aditiva**: o cliente
  v1 ignora campos desconhecidos, e `schema_version` só muda numa `/api/v2`.

### Consistência

Todas as consultas rodam numa transação SQLite *deferred* (`Household.transaction(joinable:
false)`; o padrão do Rails é `IMMEDIATE`). Em WAL, a primeira leitura (a
revisão) fixa o estado que a transação enxerga: um commit que chegue no meio não
aparece em nenhuma coleção, e a leitura nem espera nem atrasa escritores. O
`revision` do documento é, portanto, sempre o da lista, do arquivo, das viagens,
dos habituais, do dicionário e dos tombstones que vêm junto. Coberto por
`test/concurrency/sync_snapshot_concurrency_test.rb`: a leitura é pausada logo
depois de cada tabela enquanto outra conexão confirma mudanças em todas elas, e o
documento tem de sair idêntico ao anterior.

### Histórico: sem corte na v1

"Comprar de novo" (`BuyAgainSuggestions`) não é um filtro sobre as últimas
compras: agrupa **todo** o arquivo por nome normalizado, conta viagens
distintas, escolhe a grafia/categoria mais recente e exclui o que está na lista
viva. Cortar o histórico mudaria a ordem, a contagem e a grafia exibidas, e o
desfazer compra precisa dos itens da viagem mais recente. Por isso a v1 entrega
`archived_items` e `shopping_trips` **completos**, sem janela de tempo nem
limite de linhas. Receita do cliente (equivalente ao SQL do Rails, verificada
por teste contra ele):

1. Descartar linhas de `archived_items` cujo `normalized_name` esteja vazio ou
   igual ao de algum item de `list_items` (pendente ou comprado).
2. Agrupar por `normalized_name`; `trips` = nº de `shopping_trip_public_id`
   distintos do grupo.
3. A linha representante é a de maior `purchased_at` (ou `updated_at` quando
   nulo); empate vai para a que vem **depois** no array. Nome e categoria
   exibidos são os dela, e a data é a dela.
4. Ordenar por `trips` desc, data desc, nome asc (comparação binária de bytes
   UTF-8) e mostrar as 5 primeiras.

Viagem mais recente (desfazer compra): maior `finished_at`, empate para a que
vem depois no array; seus itens são os `archived_items` com o seu
`shopping_trip_public_id`.

Custo medido em 10/11/2026 (SQLite local, linhas sintéticas de 12 itens por
viagem, consultas somadas à serialização JSON; indicativo, não garantia):

| Itens arquivados | JSON | gzip | Tempo no servidor |
| --- | --- | --- | --- |
| 3.000 (ex.: 30 itens por semana, ~2 anos) | 1,3 MB | 109 KB | ~0,2 s |
| 10.000 | 4,3 MB | 363 KB | ~1,1 s |
| 50.000 | 21 MB | 1,8 MB | ~2,6 s |

No volume de duas pessoas isso cabe com folga e dispensa log incremental.
**Limite operacional:** reavaliar acima de ~20.000 itens arquivados. A saída,
quando os dados reais pedirem, é compactar o arquivo por nome normalizado ou
paginar, numa `/api/v2` (mudar a completude de `archived_items` reinterpretaria
um campo da v1). Os tombstones nunca são podados (tamanho proporcional às
remoções, ~100 bytes cada) e são enviados todos: servem ao cliente para saber que
um alvo de comando pendente foi removido (`gone`) em vez de nunca ter existido;
um item arquivado **não** gera tombstone.

### Aplicação no cliente

- Aplicar só se `revision` for **maior ou igual** à confirmada; um snapshot mais
  antigo, ou de outra casa, nunca substitui o estado local (ver "Revisões").
- O snapshot substitui o estado *confirmado* e o que não vem nele (item,
  habitual, viagem) é removido localmente; comandos locais pendentes são
  reaplicados por cima na mesma transação local e a fila nunca é apagada por uma
  leitura.
- Sinal de mudança: ActionCable entrega **dado/invalidação**, nunca HTML Turbo;
  o app aberto dispara nova sincronização, e reconexão/retorno ao app sempre
  verifica o servidor. Em segundo plano, WorkManager retenta; não prometer
  atualização instantânea com o app fechado.

## Erros

| `code` | HTTP | Retentável | Significado e recuperação |
| --- | --- | --- | --- |
| `unauthorized` | 401 | não | Credencial ausente/inválida; refazer pareamento. |
| `forbidden_scope` | 403 | não | Escopo `add_only` tentou sync; pedir autorização completa. |
| `sync_unavailable` | 403 | não | Sem senha da casa configurada; pareamento de sync indisponível. |
| `malformed_command` | 422 | não | Envelope/campos inválidos; corrigir e reenviar com novo status local. |
| `invalid_name` | 422 | não | Nome vazio após normalização, como na PWA; `data.index` diz qual item. Mostrar no item, não descartar o texto. |
| `stale_revision` | 409 | sim, após novo snapshot | Precondição de revisão falhou; sincronizar e reavaliar. |
| `precondition_failed` | 409 | sim, após novo snapshot | Item mudou; apresentar estado atual para revisão, sem sobrescrever. |
| `not_found` | 404 | não | Registro inexistente nesta casa; remover da fila só após confirmar. |
| `gone` | 410 | não | Item removido/arquivado; não recriar implicitamente. |
| `public_id_conflict` | 409 (resultado `rejected`) | não | `add_items` reivindicou um UUID de item que já pertence a outro item desta casa (com outro nome) ou a outra casa; não revela de quem. Gerar outro UUID. |
| `idempotency_conflict` | 409 | não | Mesmo `id` com conteúdo diferente ou de outra linhagem; erro de cliente, nunca aplicar. |
| `dependency_not_applied` | — (resultado `blocked`) | sim, quando a dependência for registrada | `depends_on` ainda sem registro na mesma linhagem; sem efeito. |
| `invalid_previous_token` | 422 | não | Pareamento com `previous_token` que não é de uma credencial `sync` desta casa. |
| `unsupported_command` | 422 | não | Tipo desconhecido nesta versão; aparelho antigo deve avisar. |
| `rate_limited` | 429 | sim | Aguardar e retentar com o mesmo `id`. |
| `internal_error` | 500 | sim | Retentar com o mesmo `id`; a idempotência evita efeito duplo. |

Dentro de um lote, os códigos aparecem como `error` do resultado do comando
(`conflict` para `precondition_failed`, `rejected` para os demais) com HTTP 200 no
lote; o HTTP da tabela vale quando é a requisição inteira que falha. `data`
carrega o detalhe estruturado (`reason`, `index`, `public_id`, `field`,
`current`, `expected_lock_version`) e só descreve registros da casa da
credencial.

Todo erro definitivo em um comando marca seus dependentes como `blocked` e
preserva o rascunho/intenção local. No fluxo normal de adicionar, nada disso
vira modal: mostra-se "Salvo neste aparelho"/"Aguardando sincronização" e, para
conflito persistente, um aviso recuperável.

## Dependências causais e conflitos

- O servidor aplica os comandos de um lote na ordem enviada e considera
  `depends_on`. Um comando que depende de outro não aplicado fica `blocked` sem
  efeito (regras completas em "Idempotência e deduplicação").
- Conflito nunca sobrescreve silenciosamente uma versão desconhecida: o item
  entra em revisão com o estado atual e a intenção local preservada.
- Marcar/desmarcar envia **estado desejado**, nunca toggle: reenvio não inverte
  o estado. Já estar no estado desejado é `noop`, não uma nova transição.

### Exemplos de conflito fechados

1. **Dois aparelhos adicionam o mesmo nome.** Um item pendente canônico; ambos
   recebem o mesmo `public_id` (o segundo por alias). Sem duplicata.
2. **Nome comprado é digitado de novo.** Revive pela regra atual (categoria
   atual do dicionário), desde que o item não tenha sido alterado/arquivado
   desde a observação local (`revives` em `add_items`, com o `lock_version` visto);
   senão, `precondition_failed` e o rascunho fica para revisão — não revive
   silenciosamente uma compra nova.
3. **Marcar/desmarcar reenviado.** Estado desejado idempotente: reenvio do mesmo
   UUID devolve `duplicate`; estar já no estado desejado é `noop`.
4. **Duas marcações para o mesmo estado.** Sucesso sem nova transição, se o item
   ainda está na lista.
5. **Marcar versus desmarcar sobre revisão antiga.** A primeira mudança válida
   vence; a outra recebe `precondition_failed`, sem sobrescrita silenciosa.
6. **Edição ou remoção de item que mudou.** Preserva rascunho/intenção local e
   apresenta o estado atual para revisão; não sobrescreve nem remove uma versão
   desconhecida.
7. **Ação sobre item removido ou arquivado.** `gone`; não recriar implicitamente.
   Só `add_items`/`restore_item` explícitos restauram.
8. **Compra feita offline.** Envia IDs e versões dos comprados vistos neste
   aparelho; nunca arquiva todos os comprados que existirem ao receber.
9. **Um item dessa compra mudou enquanto isso.** Finalização atômica: se alguma
   precondição falhar, nada é arquivado parcialmente; mostra-se os itens a
   revisar e preserva-se o registro local da intenção.
10. **Duas finalizações sobre os mesmos itens.** O primeiro comando válido
    arquiva; repetir o mesmo UUID devolve resultado; outro UUID não cria viagem
    vazia nem duplicada.
11. **Desfazer remoção.** Compensa a operação identificada, sem duplicar um
    pendente nem sobrescrever alterações posteriores.
12. **Desfazer marcação.** Restaura status/`purchased_at` observados e a versão
    de origem; rejeição mantém os dados e explica o motivo (janela curta só no
    aparelho que agiu, mas funciona offline como comando compensatório).
13. **Desfazer compra.** Só a viagem mais recente, como hoje, e com
    precondições; rejeição mantém os dados e explica o motivo.
14. **Habituais concorrentes.** Unicidade por nome normalizado; remover um
    habitual não altera a lista nem o histórico.

Exemplo completo: Ana conclui Leite e Pão sem sinal. Isaac adiciona Café e marca
Ovos. Ao sincronizar a conclusão, Café e Ovos ficam na lista, pois não estavam
entre os itens enviados. Se Isaac desmarcou o mesmo Leite antes, a conclusão
recebe `precondition_failed` em vez de arquivar algo que agora está pendente.

## Fora de escopo de v1

- Cadastro público, múltiplas casas pela interface, iOS, cobrança.
- Log incremental de eventos, CRDT, sincronização direta entre celulares.
- Reescrita do widget para Compose (ele reusa provider/layout atuais e só troca
  o envio online por gravação local).
- PDF: o contrato não elimina a exportação; ela é inventariada em
  [android-parity-inventory.md](android-parity-inventory.md) e resolvida na
  task própria (#57), possivelmente a partir de dados locais.
