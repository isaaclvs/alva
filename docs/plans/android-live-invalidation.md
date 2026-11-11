# Atualização nativa em primeiro plano — #46

O cliente Alva Dev usa o [endpoint Cable nativo](android-sync-invalidation.md)
somente enquanto as telas estão retomadas e há credencial sync disponível.
`ForegroundInvalidation` observa acesso/ciclo de vida; `OkHttpCableConnector`
cuida de framing ActionCable. OkHttp **4.12.0** é dependência debug, como o
MockWebServer já usado nos testes. Release TWA e seu widget continuam iguais.

## Conexão e leitura

Handshake envia Authorization Bearer, Origin correspondente ao servidor e
subprotocolo actioncable-v1-json. Segredo não vai na URL ou em logs; redirects
são desativados inclusive ao injetar um cliente HTTP. Não há exceção TLS para
servidor remoto. HTTP sem TLS continua restrito a loopback no ServerEndpoint.

Depois de welcome, assina SyncCable::Channel. Cada confirmação de inscrição
(incluindo reconexão) pede um ciclo/snapshot, mesmo sem receber mensagem de
mudança. Invalidações só aceitam revisões inteiras não negativas; nomes, HTML
e mensagens inesperadas não são aplicados ao estado. Revisões repetidas ou
anteriores são ignoradas. Pausar fecha o socket e cancela a espera de reconexão;
callbacks atrasados ficam numa sessão cancelada e não pedem novos ciclos.

O SyncController compartilha exclusão mútua com WorkManager e botão. Sinal
remoto ou commit local com app retomado contorna o intervalo de 15 s; sinais
recebidos durante um ciclo ficam agrupados num sucessor, inclusive quando o
ciclo em andamento pertence ao worker. Nenhum envio/snapshot fica dentro de
uma transação Room. Salvar continua confirmando o commit local; o pedido ao
ciclo não aguarda HTTP. WorkManager continua sendo agendado após Saved, mesmo
quando não existe tela/assinatura ativa. Duplicados e falhas locais não pedem
sincronização nova.

## Falhas e limites

Perda de conexão e HTTP 5xx/408/425/429 reconectam com espera exponencial de
1 a 30 s enquanto o app estiver retomado. Uma inscrição confirmada reinicia a
espera. HTTP 401/403 ou disconnect unauthorized marca a credencial exata para
reconexão, preservando-a para previous_token e conservando banco/fila. Recusa
atrasada de um token antigo não revoga o novo. TLS inválido, erro permanente de
handshake ou inscrição recusada não repetem em laço; nova retomada pode tentar
outra vez. O backoff não promete um prazo exato de Retry-After.

Não há polling. Com app fechado, as garantias e atrasos são os do WorkManager.
Se o socket continuar ativo mas um ciclo HTTP falhar, o resultado continua no
painel; mudança seguinte, retomada ou tentativa manual verifica novamente. O
canal é um aviso recuperável, não um log de eventos ou confirmação de comandos.

## Validação

- JVM: headers e ausência de redirects, assinatura/protocolo, revisão estrita,
  recusas HTTP, revisões repetidas, pausa/callback tardio, reconexão e sucessor
  durante ciclo de worker. O teste do wrapper verifica commit → agendamento
  persistente → notificação de primeiro plano, somente em Saved.
- Rails real isolado: script/android_rails_transport_check agora inclui
  RailsCableTransportTest. Login e inclusão pelas rotas PWA produzem aviso no
  WebSocket e snapshot no cliente Kotlin; retomar recupera inclusão feita com
  assinatura fechada. Tokens add_only/revogados não conseguem assinar. Nenhum
  teste usa a casa real.
- Android + Room + Rails real: script/android_rails_sync_check inclui o cliente
  em primeiro plano recebendo inclusão/marcação de outro banco, instalando o
  snapshot e recuperando alteração após pausa. A classe também mantém os cenários
  de reenvio, aliases, fila ordenada e perda de resposta, totalizando 6 cenários.
- Compose/emulador: ShoppingScreensTest verifica socket fechado em CREATED e
  reaberto em RESUMED; os fluxos locais e MainActivityAccessTest continuam sendo
  executados com graph de teste, sem conexão de produção.

A experiência nos dois celulares reais ainda precisa ser conferida nesta
entrega; o aceite físico anterior cobria a primeira fatia offline, não o canal.
