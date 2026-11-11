# Invalidação nativa — #46, corte Rails

Endpoint WebSocket `/api/v1/cable` (`wss` em produção), separado do `/cable`
web. Enviar `Authorization: Bearer <credencial sync>` no handshake, nunca na
URL. O cliente nativo deve enviar `Origin` correspondente à origem HTTPS do
servidor; as verificações de origem existentes continuam ativas.

Protocolo ActionCable v1 (`actioncable-v1-json`): depois de `welcome`, enviar:

```json
{"command":"subscribe","identifier":"{\"channel\":\"SyncCable::Channel\"}"}
```

Só esse canal é permitido nesse endpoint. A casa vem da credencial, nunca de
parâmetros. Token de widget, cookie web, token revogado e token anterior à
rotação da senha não autorizam a conexão. O endpoint web mantém seu contrato.
A credencial é revalidada antes de cada comando e antes de entregar cada
invalidação; revogação fecha a conexão sem pedir reconexão automática quando
houver atividade. Um socket ocioso pode permanecer aberto até a próxima
atividade, sem receber dados após a revogação.

Dentro de `message` do frame ActionCable:

```json
{"type":"invalidate","household_revision":12,"dictionary_revision":3}
```

Não há nomes, itens nem HTML. Mudanças de casa, inclusive PWA, lotes e compras
com `update_all`, publicam após commit no ponto de incremento da revisão.
Dicionário global notifica todas as casas. Rollback e inclusão duplicada não
notificam. Eventos podem se repetir ou ser agrupados; não são log de mudanças.
O adaptador é compartilhado com Cable web, inclusive `async` em desenvolvimento.

## Próximo corte Android

Assinar só enquanto o app estiver em primeiro plano, agrupar sinais durante
um ciclo e verificar snapshot após confirmação de inscrição/reconexão, mesmo
sem receber invalidação. Não instalar payload do socket como estado local.
Uma revisão recebida durante o ciclo pede verificação seguinte; eventos
repetidos não devem gerar laço. Ao perder acesso, preservar fila e solicitar
reconexão pela interface existente. WorkManager continua responsável pelo envio
persistente; app fechado não promete receber atualizações instantaneamente.
A integração Android ainda não está entregue por este corte; #46 segue aberta.

## Validação local

Testes focados de conexão/canal, publicação após commit/rollback, isolamento da
casa, dicionário global, compatibilidade do cookie web e regressões de revisão,
adição e marcação. A integração WebSocket real com Android será verificada no
corte do cliente, incluindo reconexão e mudanças originadas na PWA.
