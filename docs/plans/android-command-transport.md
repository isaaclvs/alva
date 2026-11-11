# Envio de comandos no Android (issue #45, terceiro corte)

Este corte entrega **uma tentativa segura de enviar um comando já preparado**:
`POST /api/v1/commands` com a credencial `sync`, o documento congelado byte a byte e um
recibo tipado e validado de volta. **Não** é o remetente da fila: não ordena, não faz
lote, não repete sozinho, não aplica resultados nem aliases, não baixa snapshot e não
dispara por ciclo de vida, WorkManager ou ActionCable. Nenhuma tela espera HTTP para
salvar ou marcar um item.

Código (variante debug, pacote `sync`): `CommandSender`, `CommandTransport` /
`HttpCommandTransport` e `CommandReceipt`. O HTTP comum com o pareamento
(`HttpExchange`, em `access`) foi extraído de `HttpHouseAccessApi`.

## Uma tentativa

`CommandSender.attempt(commandId)`, nesta ordem:

1. `WirePreparer.prepare`: congela o documento a partir da **intenção** (nunca da
   projeção) ou devolve o já congelado, byte a byte. Recusa → nada é enviado.
2. `HouseAccess.credentialForSync()`. Sem credencial → nada é enviado.
3. `WireOutbox.beginAttempt`: transação Room própria que soma `attempts` e marca
   `IN_FLIGHT`; já foi confirmada (commit) quando retorna. Só então toca a rede. **Nunca
   chamar dentro de uma transação Room** e nunca envolver a rede em uma.
4. `CommandTransport.send`: uma requisição, sem redirects, sem retentativa. O corpo é
   `{"commands":[<documento>]}` por concatenação; o documento não é lido nem
   reescrito. O corpo vai com tamanho fixo, o que impede a pilha HTTP de reenviar o POST
   em silêncio quando a resposta se perde (uma requisição na rede = uma tentativa contada).
5. Credencial recusada (401/403) → `HouseAccess.reportUnauthorized(credencial usada)`.
   Só a credencial em uso é marcada: a resposta atrasada de uma credencial antiga não
   derruba a nova.

`SendAttempt.NotSent` (com `NotSentReason`) não conta tentativa e não toca a fila.
`SendAttempt.Sent` traz o número da tentativa e um `CommandOutcome`:

| `CommandOutcome` | Quando | O que fazer depois (corte futuro) |
| --- | --- | --- |
| `Receipt` | 200 com resultado válido **deste** comando. O `status` pode ser `applied`, `noop`, `duplicate`, `conflict`, `rejected` ou `blocked` | o aplicador futuro confirma ou trata o conflito |
| `Transient` | rede, timeout, TLS, 5xx, 429 (`retryAfterSeconds`) | repetir o **mesmo** documento depois; `duplicate` se o servidor já tinha confirmado |
| `Unauthorized` | 401/403 | reconectar; fila e documento intactos |
| `Refused` | 422/outros 4xx na requisição inteira, ou documento acima do limite | repetir os mesmos bytes não ajuda |
| `Invalid` | resposta que não bate com o contrato ou com este comando | não confirma nada |

HTTP 200 não é sucesso de todos os comandos: o `status` do resultado é que decide.

## O que garante e o que não faz

- Falha de rede, timeout, resposta perdida ou recibo recebido e não aplicado **não
  apagam nem alteram** a intenção nem o documento; só o contador e `IN_FLIGHT` (escritos
  *antes* da requisição) mudam. Nenhum comando é removido nem marcado confirmado aqui.
- Se o processo morrer antes de um recibo ser aplicado, a fila e o documento continuam e a
  repetição recebe `duplicate` com `original_status`, `revision` e `data` originais.
- Recibo validado: um único resultado, `id` igual ao enviado, `status` conhecido,
  `revision` inteira ≥ 0 e não maior que a da casa ao fim do lote, `original_status` em
  `duplicate`, `error` em `conflict`/`rejected`/`blocked`, corpo completo (corpo menor que o
  `Content-Length`, truncado ou com texto extra não vale). Os `data` seguem como JSON
  intacto para o aplicador.
- Segurança: `ServerEndpoint` (https, ou loopback em debug), sem seguir redirects, limites
  de corpo (documento 256 KiB, resposta 1 MiB), timeouts, nenhum log, nenhum segredo ou
  corpo de erro em resultados, exceções ou mensagens.
- Uma chamada de `attempt` por vez por instância (mutex): um processo não mantém duas
  requisições em voo pelo mesmo remetente.

## Ainda pendente na #45

Aplicar recibos e aliases (e remover comandos confirmados) numa transação; ordem da fila,
lotes e dependências entre comandos (enviar um dependente antes da dependência só rende
`blocked`); snapshot e reconciliação; sincronizar ao abrir/retomar; dois aparelhos
convergindo. Nada disso está pronto e não deve ser afirmado.

## Como foi validado

- JVM (`HttpCommandTransportTest`, `CommandReceiptParserTest`): servidor HTTP controlado
  (bytes enviados, cabeçalhos, 401/403, 429 com `Retry-After`, 5xx, 4xx, redirect não
  seguido, timeout, corpo truncado, resposta grande, uma requisição por chamada).
- Instrumentado (`CommandSenderTest`, Room real em arquivo + servidor HTTP em loopback): a
  tentativa já está confirmada no banco quando a requisição chega (lida por outra
  conexão), documento idêntico em cada repetição, nenhuma recusa chega à rede, nenhuma
  resposta remove fila, recibo perdido com o processo reaberto, credencial refletida.
- **Rails real**: `script/android_rails_transport_check` sobe `bin/rails server`
  (`RAILS_ENV=test`, SQLite temporário em diretório temporário, só fixtures, 127.0.0.1),
  emite credenciais e roda `RailsCommandTransportTest` com o cliente Kotlin de
  transporte: aplicar e reenviar (`duplicate`), **resposta perdida depois do commit**
  (um proxy de teste, `ResponseDroppingProxy`, lê a resposta completa do Rails e fecha a
  conexão sem repassá-la; não existe endpoint de falha em produção), dependente
  `blocked`, conflito, rejeição, `idempotency_conflict`, credenciais recusadas. O script
  falha se o teste foi pulado. No CI é o job `android-rails-transport`.

Limites: a parte Rails roda na **JVM**, não no Android; prova o contrato e o código HTTP/
recibo contra o Rails de verdade, mas não a pilha de rede do emulador nem Room junto com
Rails. Um E2E Android contra Rails (emulador + `adb reverse`) fica para o corte que
ligar o remetente ao ciclo de vida. Convergência de dois aparelhos e snapshot são cortes
seguintes.
