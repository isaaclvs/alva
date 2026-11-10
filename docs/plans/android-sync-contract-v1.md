# Contrato de sincronização v1 (proposta)

Data: 10/11/2026. Status: **proposto** — a direção arquitetural e a escolha de
Kotlin estão aprovadas ([ADR 0001](../adr/0001-android-offline-com-rails.md)).
Pareamento, escopos e a barreira de autenticação da `/api/v1` já estão
implementados (issue #35, ver Autenticação); o restante do contrato ainda não
foi implementado nem validado. Ele existe para dar um alvo versionado às tasks
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
`/api/v1`; snapshots e comandos continuam proposta.

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
  reautorização e nova carga.

### Emissão, revogação e rotação

Emissão (nova credencial `sync`, mostrada uma vez):

```bash
curl -sS -X POST https://<host>/api/v1/devices \
  -H 'Content-Type: application/json' \
  -d '{"password":"<senha da casa>","name":"Celular da Ana"}'
```

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

Um lote de comandos de um mesmo aparelho, em ordem:

```json
{
  "device_id": 12,
  "observed_revision": 840,
  "commands": [
    {
      "id": "5f0c2f0e-...-uuid",
      "type": "add_items",
      "depends_on": "a1b2...-uuid-ou-null",
      "observed_revision": 840,
      "preconditions": { "lock_version": 7 },
      "payload": { "names": ["Leite", "Pão"] }
    }
  ]
}
```

Resposta:

```json
{
  "revision": 841,
  "results": [
    {
      "id": "5f0c2f0e-...-uuid",
      "status": "applied",
      "revision": 841,
      "data": { "items": [ { "public_id": "...", "name": "Leite", "outcome": "created" } ] }
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
- `observed_revision` é a revisão da casa que o aparelho conhecia; serve de
  precondição quando o comando não manda uma precondição mais específica.
- `preconditions` é específico do comando (ex.: `lock_version` do item).
- `status` de cada resultado: `applied`, `duplicate` (mesmo UUID já processado),
  `noop` (já estava no estado desejado), `conflict` (precondição falhou),
  `rejected` (erro definitivo) ou `blocked` (dependente de comando não aplicado).
- A ordem dos `results` é a ordem dos comandos enviados.

## Comandos v1

| `type` | `payload` | `preconditions` | Equivalente atual |
| --- | --- | --- | --- |
| `add_items` | `names: []` (já separados no aparelho) | — | `Household#add_items` |
| `set_item_state` | `public_id`, `desired: "pending"\|"purchased"` | `lock_version` | `ListItem#toggle_purchased!` (estado desejado, nunca toggle) |
| `undo_item_state` | `public_id`, `status`, `purchased_at` | `lock_version` | `PurchaseUndo` / `#undo_purchase!` |
| `correct_item` | `public_id`, `name`, `category_id?` | `lock_version` | `ListItem#correct` + `ItemCategorizer.learn` |
| `remove_item` | `public_id` | `lock_version` | `ListItemSnapshot` + `destroy` |
| `restore_item` | campos do item removido (nome, categoria, `purchased_at`, `created_at`, `position`) | item não existe / não há pendente homônimo | `Household#restore_item` |
| `finish_shopping` | `items: [{ public_id, lock_version }]` — só os comprados vistos neste aparelho | cada `lock_version` | `Household#finish_shopping!` |
| `undo_shopping` | `trip_public_id` | viagem é a mais recente | `Household#undo_shopping!` |
| `create_habitual` | `name` | — | `HabitualItemsController#create` |
| `remove_habitual` | `public_id` | — | `HabitualItemsController#destroy` |
| `add_habituals` | `public_ids: []` ou `all: true` | — | `#add_selected` / `#add_all` |

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

- O servidor processa cada comando sob transação/lock da casa e guarda o
  resultado por `(household_id, command_id)`, ligado a aparelho e ao digest do
  conteúdo.
- Reenvio do mesmo `id` com o mesmo conteúdo devolve o mesmo resultado
  (`duplicate`), sem segundo efeito — inclusive depois de o cliente perder a
  resposta já comprometida no servidor.
- Reuso do mesmo `id` com conteúdo diferente é rejeitado (`idempotency_conflict`)
  em vez de aplicar algo ambíguo.
- UUIDs de operações e exclusões (tombs) são mantidos enquanto puderem ser
  reenviados; nenhum prazo arbitrário que permita duplicar uma compra após um
  período longo offline.

## IDs públicos e aliases

- Os IDs inteiros do Rails continuam válidos internamente; o contrato expõe um
  `public_id` (UUID) por registro.
- Item criado offline já nasce com `public_id` gerado no aparelho; o servidor
  cria a linha com esse mesmo `public_id`, para que comandos posteriores o
  alcancem.
- Quando dois aparelhos adicionam o mesmo nome, o servidor mantém **um** item
  canônico. O `public_id` perdedor vira alias do canônico
  (`sync_aliases`), e comandos que citam o alias são resolvidos para o canônico.
- Aliases são mantidos pelo mesmo critério dos UUIDs de operação: enquanto
  houver comando em trânsito que possa citá-los.

## Revisões

- Cada casa tem uma revisão monotônica (`sync_revision`), incrementada na mesma
  transação de qualquer mudança compartilhada — inclusive as que a PWA faz com
  `update_all` (finalizar compras) e remoções. `updated_at` não é cursor.
- Snapshots e resultados carregam a revisão. O aparelho guarda a última revisão
  confirmada.
- Um snapshot com revisão **menor** que a confirmada nunca é aplicado; uma
  leitura remota nunca apaga a fila local.
- Comandos usam `observed_revision`/`lock_version` como precondição; conflito
  bloqueia dependentes causais, não ações independentes.

## Snapshot

- `GET /api/v1/snapshot` devolve o conjunto sincronizado completo com a revisão
  atual: categorias, dicionário (`CatalogItem`, incluindo aprendidos), itens da
  lista viva, itens arquivados necessários a "comprar de novo" (limitado),
  habituais e tombs de exclusão desde a revisão enviada.
- Preferir snapshot completo ao volume doméstico a montar log incremental sem
  necessidade. Um snapshot parcial só se dados reais exigirem.
- Aplicação no cliente: atualizar estado confirmado e reaplicar comandos locais
  ainda pendentes na mesma transação local.
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
| `invalid_name` | 422 | não | Nome vazio/longo demais; mostrar no item, não descartar o texto. |
| `stale_revision` | 409 | sim, após novo snapshot | Precondição de revisão falhou; sincronizar e reavaliar. |
| `precondition_failed` | 409 | sim, após novo snapshot | Item mudou; apresentar estado atual para revisão, sem sobrescrever. |
| `not_found` | 404 | não | Registro inexistente nesta casa; remover da fila só após confirmar. |
| `gone` | 410 | não | Item removido/arquivado; não recriar implicitamente. |
| `idempotency_conflict` | 409 | não | Mesmo `id` com conteúdo diferente; erro de cliente, nunca aplicar. |
| `unsupported_command` | 422 | não | Tipo desconhecido nesta versão; aparelho antigo deve avisar. |
| `rate_limited` | 429 | sim | Aguardar e retentar com o mesmo `id`. |
| `internal_error` | 500 | sim | Retentar com o mesmo `id`; a idempotência evita efeito duplo. |

Todo erro definitivo em um comando marca seus dependentes como `blocked` e
preserva o rascunho/intenção local. No fluxo normal de adicionar, nada disso
vira modal: mostra-se "Salvo neste aparelho"/"Aguardando sincronização" e, para
conflito persistente, um aviso recuperável.

## Dependências causais e conflitos

- O servidor aplica os comandos de um lote na ordem enviada e considera
  `depends_on`. Um comando que depende de outro não aplicado fica `blocked` sem
  efeito.
- Conflito nunca sobrescreve silenciosamente uma versão desconhecida: o item
  entra em revisão com o estado atual e a intenção local preservada.
- Marcar/desmarcar envia **estado desejado**, nunca toggle: reenvio não inverte
  o estado. Já estar no estado desejado é `noop`, não uma nova transição.

### Exemplos de conflito fechados

1. **Dois aparelhos adicionam o mesmo nome.** Um item pendente canônico; ambos
   recebem o mesmo `public_id` (o segundo por alias). Sem duplicata.
2. **Nome comprado é digitado de novo.** Revive pela regra atual (categoria
   atual do dicionário), desde que o item não tenha sido alterado/arquivado
   desde a observação local; senão, `precondition_failed` e o rascunho fica
   para revisão — não revive silenciosamente uma compra nova.
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
