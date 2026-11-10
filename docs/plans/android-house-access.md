# Acesso à casa no Android nativo — issue #44

Status: **implementado na variante debug**; a release (TWA) não inclui este
módulo até a transição. Acompanhamento: [#33](https://github.com/isaaclvs/alva/issues/33).
Regras de autenticação do servidor em
[android-sync-contract-v1.md](android-sync-contract-v1.md#autenticação-e-autorização);
este documento descreve o lado do aparelho.

## Onde está o código

`android/app/src/debug/java/net/ts/taild80ca0/atlas/twa/access/`. Sem dependência
nova no app (Keystore, `HttpURLConnection`, Room e Compose já estavam em debug);
`mockwebserver` entra só nos testes. A tarefa `verifyAccessPolicy` (parte de
`verifyVariants`) garante que a política de backup e a regra de rede abaixo só
existem na debug. Ao migrar a entrada padrão, o código e as regras sobem para
`src/main`.

## Fluxo

| Momento | O que acontece | Rede | Senha |
| --- | --- | --- | --- |
| Primeira configuração | `POST /api/v1/devices` com a senha → credencial `sync`; `GET /api/v1/snapshot` só para ler `household.public_id`; vínculo da casa; credencial guardada | sim | digitada uma vez, nunca guardada |
| Abrir o app depois | lê o cofre local e abre | **não** | não |
| Token recusado (revogado, senha rotacionada) | o aparelho passa a `NeedsReconnect`; banco e fila intactos | — | — |
| Reconexão | mesmo `POST` com a senha **e** `previous_token` = segredo antigo; mesma casa | sim | digitada de novo |

`HouseAccess` serializa as chamadas (dois toques não pareiam duas vezes) e expõe
`credentialForSync()` para o futuro remetente (#45); quando o servidor responder
401/403 a um comando, o remetente chama `reportUnauthorized(credential)`. Só a
credencial **em uso** é marcada: a resposta atrasada de uma credencial antiga não
derruba a nova.

## Credencial: Keystore, sem backup, sem senha

- `KeystoreCredentialVault` cifra um registro JSON com AES-256-GCM, chave
  **não exportável** no Android Keystore (`AndroidKeyStore`, sem autenticação do
  usuário, para o widget e o sync em segundo plano funcionarem com o aparelho
  bloqueado). IV novo por escrita, formato versionado, AAD fixo.
- O arquivo fica em `noBackupFilesDir` e é gravado de forma atômica
  (`AtomicFile`: arquivo temporário + fsync + rename).
- Leitura devolve `Empty`, `Found`, `Lost` (chave sumiu — reinstalação, restauração,
  Keystore limpo — ou arquivo adulterado/truncado: o segredo é irrecuperável) ou
  `Unavailable` (falha transitória). `Unavailable` **nunca** vira `Lost`: o app
  não cria uma identidade nova só porque o armazenamento falhou agora.
- A senha da casa não é entrada do cofre, do banco, do estado nem de logs. Só o
  campo da tela a recebe e ele é esvaziado ao enviar. `AccessCredential.toString()`
  mascara o token; nenhuma mensagem de erro, exceção ou texto da UI carrega
  segredo, código HTTP ou detalhe interno.
- O registro guarda `active`, `pending` e `needs_reconnect` (abaixo).

### Política de backup (somente debug)

| Dado | Backup / transferência |
| --- | --- |
| Cofre (`no_backup/`) | **excluído** (`dataExtractionRules` nas duas seções e `fullBackupContent`) |
| Preferências cifradas do widget (`alva_quick_add.xml` e o keyset do `EncryptedSharedPreferences`) | **excluídas** na debug (texto cifrado sem a chave do Keystore é inútil) |
| Banco Room (lista e fila) | **mantido**: é a única cópia de ações que nunca saíram do aparelho |

Uma restauração traz o banco sem credencial: o app abre em "Reconectar à casa",
só aceita a mesma casa e preserva a fila. Mesmo que um arquivo do cofre fosse
restaurado, ele não decifra sem a chave do Keystore daquele aparelho e lê como
`Lost`. `allowBackup` continua `true` (é o que permite restaurar o banco).

**Release:** a política **não** foi alterada — a release é o TWA e o widget atual
(`alva_quick_add`, token `add_only`) continua com as regras padrão. Um arquivo
restaurado sem chave já falha com segurança (o widget pede nova conexão), mas
excluí-lo do backup na release fica para a transição.

## Casa: `sync_state.household_public_id`

- O vínculo usa a coluna existente, sem migração Room. `RoomHouseholdBinding.bind`
  é *compare-and-set* numa transação: grava se vazio, aceita a mesma casa, **recusa
  outra** sem alterar nada (nem lista, nem fila, nem a linha).
- `confirmed_revision` **não é tocado**: fica como estava (0 se a linha é nova). Ele
  afirma que a lista inteira foi reconciliada, e identificar a casa não prova isso.
  Só a reconciliação (#45) o move. O snapshot lido para identificar não é aplicado.
- Se o servidor é de outra casa, a credencial recém-emitida é descartada (a que o
  aparelho já tinha é mantida) e a UI diz que nada foi misturado nem apagado.
  O servidor continua com um aparelho órfão: não há endpoint para o cliente revogar.

## Reautorização sem perder a identidade

A linhagem do servidor (`lineage_id`) só continua se o pedido traz o segredo
anterior como `previous_token`. Por isso:

1. O segredo antigo (`active`) **permanece** no cofre, marcado `needs_reconnect`,
   até a nova credencial estar guardada **e** a casa confirmada.
2. A credencial nova é gravada como `pending` **antes** de identificar a casa
   (o servidor não a mostra de novo). Se a identificação falha, a próxima tentativa
   termina com ela, sem novo pareamento (que tem limite de 5 por 15 minutos).
3. Resposta perdida depois do commit no servidor: o aparelho só tem o segredo
   antigo, reenvia o mesmo `previous_token` e a nova credencial herda a mesma
   linhagem (o aparelho órfão do pareamento perdido fica na mesma linhagem).
4. `pending` recusado (401) ainda serve de `previous_token`: é da linhagem.
5. `invalid_previous_token` (o servidor não conhece o segredo) é mostrado como erro;
   o cliente **não** cai em silêncio para uma identidade nova.

Limite assumido (igual ao contrato): se o Keystore foi apagado e o segredo antigo se
perdeu (`Lost`), só é possível uma linhagem nova. Comandos antigos da fila podem
responder `idempotency_conflict`; cabe ao envio/reconciliação (#45) sincronizar,
ver o que foi aplicado e reemitir o que faltar com UUIDs novos.

## Rede

`ServerEndpoint` só aceita `https` (host qualquer) ou `http` para o loopback do
próprio aparelho (`localhost`, `127.0.0.1`, `::1`), sem usuário, caminho ou
consulta. O TLS nunca é afrouxado: não há `TrustManager`/`HostnameVerifier`
próprio. Redirecionamentos **não** são seguidos (levariam a senha a outro host).
`network_security_config` (debug) libera texto claro somente para `127.0.0.1` e
`localhost` — como o emulador chega a um Rails local via `adb reverse` e como os
testes falam com o servidor de teste. O endereço vem de `BuildConfig.SERVER_URL`
(padrão `https://atlas.taild80ca0.ts.net`; `-PalvaServerUrl=http://127.0.0.1:3000`
para um Rails local).

O snapshot é lido inteiro só para extrair o id da casa (limite de 32 MB, acima do
ponto em que o contrato já manda reavaliar a v1); a leitura incremental fica para
quando o envio (#45) o precisar.

## Interface (debug)

Uma tela mínima em `MainActivity`: "Conectar à casa" com o campo da senha (uma vez);
"Reconectar à casa" quando o acesso foi recusado, com o aviso de que o que foi
adicionado no aparelho foi mantido; e, conectado, "Conectado à casa" com
"Verificar conexão". Textos em português, sem código HTTP, nome de campo ou
exceção. As telas da lista são da #43.

## Testes

- Cenários compartilhados (`sharedTest/.../HouseAccessScenarios`), executados **duas
  vezes** contra um servidor HTTP real em loopback (`FakeAlvaServer`, MockWebServer
  seguindo o contrato e os testes Rails): na JVM com armazenamento em memória e no
  emulador com o Keystore e o Room reais. Cobrem: primeira configuração, abertura
  local sem rede, senha errada/sem senha/limite/servidor/sem rede/resposta
  malformada/redirecionamento, revogação, rotação, resposta perdida,
  identificação interrompida, `previous_token` desconhecido, outra casa, concorrência,
  armazenamento que falha ou ilegível e mensagens sem detalhe interno.
- Instrumentados (`connectedDebugAndroidTest`): cofre no Keystore real (chave não
  exportável, texto cifrado, IV novo, arquivo adulterado/truncado/sem chave,
  escrita que falha), varredura de **todos** os arquivos do app procurando a senha e
  o token, `RoomHouseholdBinding` (revisão preservada, outra casa recusada, corrida),
  revogação→reconexão com fila real preservada, restauração do banco sem credencial,
  política de backup e rede lida do manifesto **instalado**, e a tela no aparelho
  (primeira abertura pede a senha, abertura seguinte não, revogado reconecta).
- Validação manual em 10/11/2026 (emulador API 36, Rails de desenvolvimento real em
  `127.0.0.1:3010` via `adb reverse`, APK debug com `-PalvaServerUrl`; não roda em CI):
  senha errada → mensagem; configuração com a senha real; app reaberto **com o
  servidor parado** abre direto; revogação no servidor → "Verificar conexão" oferece
  reconectar (e continua oferecendo após reiniciar o app); reconexão cria um
  aparelho novo na **mesma linhagem** (`lineage_id`); nem a senha nem o token
  aparecem nos arquivos do app, e o `development.log` do Rails mostra
  `password`/`previous_token` como `[FILTERED]`.
- Backup real com `adb backup`/`adb restore` (app debuggable): o backup traz o banco
  Room e **não** traz `no_backup/alva-access.bin`; após `uninstall` + `restore` o app
  abre em "Reconectar à casa" (banco restaurado, credencial ausente). Observação
  honesta: `no_backup/` já é excluído por padrão pelo Android; as regras explícitas
  documentam e travam a intenção (e cobrem as preferências cifradas do widget, que
  não existiam nesse teste). O `bmgr` com o `LocalTransport` do emulador falhou ao
  restaurar (`PM agent has no metadata`), então não foi usado como evidência.
- Não coberto: restauração por Auto Backup na nuvem / transferência entre aparelhos
  reais (#59) e o endpoint de comandos (#39/#45): quando existir, o caso "token
  recusado durante o envio" passa por `reportUnauthorized`.
