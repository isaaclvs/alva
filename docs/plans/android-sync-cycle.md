# Ciclo de sincronização no Android (issue #45, quinto e sexto cortes)

Este corte liga as peças já integradas (documento congelado, `CommandSender`, `ReceiptApplier`) ao snapshot do
servidor e a um gatilho: **adicionar, listar e marcar offline e sincronizar depois**, entre dois aparelhos da mesma
casa. É a primeira fatia utilizável de ponta a ponta do app nativo de teste (variante debug); a release continua
TWA. Prioridade confirmada pelo usuário: só a casa dele, dois aparelhos, a lista pode começar vazia. Não faz parte:
cadastro, escolha de famílias, monetização, widget nativo e telas de histórico.

Código (pacote `sync`, debug): `ServerSnapshot`/`SnapshotParser`, `SnapshotTransport`, `SnapshotInstaller`,
`SyncCycle`, `SyncController`/`SyncMessages`. Banco Room na versão **5**.

## O que um ciclo faz

`SyncCycle.run()`, nesta ordem, sem sobreposição (uma segunda chamada devolve `AlreadyRunning`):

1. **Envia a fila**, do comando mais antigo ao mais novo, um por vez (`CommandSender.attempt`), e aplica cada
   resposta (`ReceiptApplier.apply`). Um comando só sai depois que aquele de que depende foi consumido (ou já não
   está na fila). Cada comando é tentado **no máximo uma vez por ciclo**, então nenhuma recusa vira laço.
   `CONFLICT`, `REJECTED` e `BLOCKED` não são reenviados e **não seguram** comandos que não dependem deles. Os
   bytes e o UUID são os do documento congelado, em toda retentativa. A rede nunca fica dentro de transação Room
   (a tentativa é confirmada antes da requisição; a resposta é aplicada em outra transação depois).
2. **Para** se a rede falhar (`Offline`) ou a credencial for recusada (`NotConnected`; o acesso marca a reconexão).
   O que já foi consumido fica; o resto fica como estava, com a tentativa contada.
3. **Só se nenhum comando enviado estiver sem resposta** (`ReceiptApplier.unresolvedAttempts()` vazio) lê o
   snapshot e o instala. Caso contrário, `SnapshotHeld` nomeia os comandos incertos e **nada** é lido nem
   instalado: um snapshot lido depois de outro aparelho remover/arquivar o item não o mostra, e reaplicar o
   comando antigo sobre ele o traria de volta. A saída é reenviar os mesmos bytes (o servidor responde
   `duplicate` ou a recusa real) em um ciclo seguinte. **Uma lista local vazia ou uma fila vazia nunca dispensa
   o snapshot**: um aparelho sem nada para enviar ainda precisa aprender a lista da casa por ele.

## Instalação do snapshot (`SnapshotInstaller`)

Uma transação Room, com a rede já terminada. Instala só quando instalar não perde nada:

| Recusa (`SnapshotRefusal`) | Quando |
| --- | --- |
| `NOT_BOUND` | o aparelho nunca se conectou a uma casa (o instalador nunca vincula) |
| `OTHER_HOUSEHOLD` | `household.public_id` diferente do vínculo; nada é misturado nem apagado |
| `UNRESOLVED_ATTEMPTS` | há tentativa enviada sem resposta (ver acima) |
| `OLDER_REVISION` / `OLDER_DICTIONARY` | `revision` ou `dictionary_revision` menor que o guardado (igual é aceito: reinstalar é idempotente) |
| `BEHIND_RECEIPT` | o snapshot é anterior ao recibo de um comando que este aparelho enviou: não pode ser o que o servidor tem agora |
| `ALIAS_CONTRADICTION` | o snapshot lista como item um id que o servidor disse ser alias |

Corpo inválido (`SnapshotParser`) é recusado inteiro antes de chegar aqui: `schema_version` diferente de 1,
campo ausente ou de tipo errado (um inteiro como texto ou fração não é coagido), instante que não seja UTC com até
6 dígitos, referência a categoria/viagem que não está no snapshot, id ou `normalized_name` repetido. Campos extras
são ignorados (o contrato só cresce de forma aditiva).

Instalado: o catálogo (categorias e dicionário) é trocado (`LocalCatalog.replace`), `confirmed_items` e o
histórico são trocados por inteiro (o que o snapshot não tem some do confirmado; **a fila nunca é tocada por uma
leitura**), `confirmed_revision` e o nome da casa avançam, os comandos consumidos cujo recibo o snapshot cobre são
liberados (com o recibo; ficam se um dependente ainda não preparado precisa emprestar a versão) e `list_items` é
reconstruído com a fila por cima e os aliases em vigor. Qualquer erro de SQL desfaz tudo e propaga (testado com um
`TRIGGER` que falha no último `INSERT`). Aliases nunca são apagados.

### Armazenamento (migração 4→5, aditiva)

`schemas/1-4.json` intactos. Colunas novas e nulas em `confirmed_items` (`normalized_name`, `created_at_micros`,
`updated_at_micros`, `purchased_at_micros`, `server_order`) e `sync_state.household_name`; tabelas novas e vazias
`archived_items`, `shopping_trips`, `habitual_items`, `tombstones`. Instantes ficam em **microssegundos** (os
milissegundos antigos continuam, derivados, para exibição) e `server_order` é a posição no array do servidor (a
ordem de id, que é o desempate de "Comprar de novo"). O histórico fica em tabelas à parte para a projeção, que
reaplica a fila a cada ação, não carregar um arquivo sem limite. **Nenhuma tela de histórico, de "Comprar de novo"
ou de habituais foi feita**: os dados são guardados, não interpretados.

## Gatilho

Além do [worker persistente](android-background-sync.md) da #46, `SyncController` pede um ciclo quando o app
**abre ou retoma** (`ON_RESUME`; ignorado
se o ciclo anterior começou há menos de 15 s) e quando o usuário toca em **"Sincronizar agora"** (vira
**"Tentar novamente"** se o último ciclo não terminou sincronizado; nunca limitado). O painel de estado na área rolável das telas
conta o que aconteceu em português, sem códigos: "Sincronizado.", "Sem conexão com o servidor…",
"Aguardando o servidor confirmar N alterações enviadas. A lista não foi atualizada para não perder nada…", e
acrescenta quantas alterações "precisam de atenção" (conflito/recusa/bloqueio: a intenção continua guardada e
visível na lista) ou "ainda não puderam ser enviadas". Adicionar e marcar continuam sem esperar a rede. O painel pode crescer com os erros sem deslocar o campo e o botão de adicionar para fora da área disponível acima do teclado.

Commits locais enquanto a tela está retomada, confirmação/reconexão da assinatura
[ActionCable](android-live-invalidation.md) e invalidações novas também pedem ciclos,
sem o intervalo de retomada. Sinais durante um ciclo (inclusive worker) ficam
agrupados num sucessor; salvar continua sem aguardar a rede.

## Como executar o cenário e testar o APK

Cenário de dois aparelhos contra **Rails real de teste** (banco SQLite temporário, só fixtures, `127.0.0.1`; nunca
desenvolvimento nem produção). Precisa de Ruby (`.ruby-version`), JDK 21, Android SDK e um emulador/aparelho no adb
(com várias execuções Android no mesmo computador, serialize como em [android-shopping-screens.md](android-shopping-screens.md)):

```bash
script/android_rails_sync_check            # A adiciona offline -> sincroniza -> B recebe -> B marca -> A converge,
                                           # adicionar+marcar juntos, mesmo nome nos dois, resposta perdida após o commit
script/android_rails_transport_check       # versão JVM: transporte de comandos e leitura do snapshot real
```

Os dois falham se a classe de teste foi pulada. A resposta perdida usa o `ResponseDroppingProxy` de teste (lê a
resposta do Rails e fecha a conexão); não existe endpoint de falha em produção.

APK debug (instala ao lado do atual, `applicationIdSuffix .debug`, "Alva Dev"; a release e a assinatura não mudam):

```bash
cd android && ./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
# ensaio sem produção: Rails de desenvolvimento local + adb reverse
./gradlew assembleDebug -PalvaServerUrl=http://127.0.0.1:3010 && adb reverse tcp:3010 tcp:3010 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sem `-PalvaServerUrl` o APK aponta para o servidor real (`https://atlas.taild80ca0.ts.net`). **Conectar com a senha
da casa ali é um ato deliberado**: o aparelho passa a receber a lista real e a enviar o que for adicionado ou marcado
para a casa real. Nada neste corte provisiona casa nova nem altera dados de produção. A verificação física da
primeira fatia foi confirmada pelo usuário em 11/11/2026; o novo worker tem validação própria, descrita no documento
da #46.

Ordem sugerida nos dois celulares: abrir e conectar (a primeira sincronização traz a lista, que pode estar vazia);
em A, desligar a rede, adicionar e marcar; religar e tocar em "Sincronizar agora"; em B, abrir ou retomar o app
e conferir; marcar em B; voltar a A.

## Limites conhecidos (nada disto deve ser dado como pronto)

- **Segundo plano:** o [worker da #46](android-background-sync.md) envia alterações locais com rede. Em
  primeiro plano, [ActionCable](android-live-invalidation.md) e commits locais pedem ciclos agrupados; com app
  fechado, alterações somente no outro aparelho ainda dependem da próxima abertura/retomada ou do botão.
- **Tentativa sem resposta que não pode ser repetida segura o snapshot, de forma visível e sem apagar nada.** É o
  caso de `idempotency_conflict` (credencial antiga perdida: o Keystore apagado) e de um comando `BLOCKED` que já
  tinha sido enviado. Nos dois, a pessoa pode escolher **"Usar a lista do servidor"** no painel de revisão
  ([resolução local](android-conflict-resolution.md)): o grupo sai da fila com auditoria e o snapshot volta a ser
  instalado; a reemissão com UUIDs novos ainda é manual.
- **Recompra redirecionada** (`revive_redirected`): a marcação que o aparelho segurou aparece no painel e pode ser
  descartada (ou, se tinha sido enviada sem resposta, trocada pela lista do servidor).
- **Recusa da requisição inteira (`Refused`)** não segura mais o snapshot: prova que não houve efeito, então a
  tentativa é respondida, o comando vira `REJECTED` (sem reenvio em laço) e pode ser descartado no painel.
  `Invalid` (resposta fora do contrato) não prova nada e continua reenviando os mesmos bytes no ciclo seguinte.
- **Conflitos:** recusas definitivas podem ser descartadas no painel "Revisar alterações"; reemitir com
  conteúdo corrigido ainda não existe.
- Comandos recusados antes de enviar (`VERSION_UNKNOWN` etc., tabela de recusas do documento de persistência) ficam
  parados e são contados em "ainda não puderam ser enviadas".
- O snapshot é sempre completo (sem incremental), com teto de 32 MB; um ciclo grande consome dados móveis.
- A ordem da lista viva usa `created_at` em milissegundos (relógio do servidor para o que veio dele, do aparelho
  para o que ainda é local). A ordem e a precisão do servidor ficam guardadas, mas a tela ainda não as usa.
- Testes automatizados em emulador (loopback via `adb reverse`) e JVM; a primeira fatia também foi validada nos
  dois celulares pelo usuário. Não há teste automatizado de morte real do processo no meio do ciclo (simulada
  reabrindo o arquivo).
- `SyncCycleTest` usa um servidor de mentira para provocar falhas exatas; só o `RailsSyncConvergenceTest` prova o
  contrato com o Rails de verdade.
