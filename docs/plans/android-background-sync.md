# Sincronização persistente — #46, primeiro corte

O Alva Dev usa WorkManager **2.10.1**, compatível com o toolchain atual, para
continuar um ciclo sem depender de Activity aberta. A release continua TWA;
`verifyVariants` também recusa WorkManager no classpath de release.

## Agendamento e execução

- Depois do commit de `ShoppingListStore.execute`, somente `Saved` agenda um
  trabalho. A gravação local e seu comando continuam atômicos em Room; agendar
  não faz HTTP. A conclusão do enqueue é aguardada antes de devolver sucesso à
  tela, mesmo se ela for cancelada nesse intervalo.
- Conectar ou reconectar com sucesso também agenda: a credencial e o vínculo
  já estão guardados. Isso permite reenviar a fila após recuperar o acesso.
- Uma cadeia única `alva-household-sync-v1`, com `CONNECTED` e backoff
  exponencial inicial de 30 segundos, usa `APPEND_OR_REPLACE` a cada salvamento.
  Não cancela um envio em andamento nem perde uma ação salva depois da última
  leitura desse envio. Uma cadeia cancelada não bloqueia ações novas. Cada
  salvamento pode deixar um sucessor; não há um worker por comando da fila.
- Abrir o app pede recuperação com `KEEP`, uma vez por processo. Corrige a
  janela entre o commit em Room e o enqueue em outro banco, sem prolongar uma
  cadeia já pendente. O worker nunca dispara essa recuperação ao construir seu
  grafo (isso poderia criar uma cadeia sem fim).
- O worker aguarda `SyncController.runInBackground()`, compartilhando o mesmo
  ciclo, exclusão mútua e relatório da tela. Não lança uma coroutine que
  sobreviva ao trabalho. Concorrência com o botão/retomada devolve retentativa;
  cancelamento propaga e libera o controlador. O ciclo retoma do Room e do
  Keystore, preservando documentos congelados e UUIDs.
- `WorkRequest` não carrega senha, token, dados da lista nem comandos. O
  WorkManager persiste agendamento/backoff e restaura trabalho pendente após
  morte do processo e reinício do Android.

## Quando retentar

Rede, timeout, HTTP 5xx e 429 devolvem `retry`. TLS inválido, acesso recusado,
snapshot inválido/recusado, tentativa incerta sem solução, erro de armazenamento
e conflitos preservados **não** geram retentativas automáticas. O trabalho
termina com `success` para não contaminar seus sucessores; isso significa que a
execução terminou, não que todas as intenções foram aceitas pelo servidor. O
relatório da tela continua mostrando o resultado e as pendências. Uma ação
nova, reconexão, próxima abertura ou tentativa manual pode pedir outro ciclo.

## Limites

O Android decide quando executar: Doze, bateria e restrições do fabricante
podem adiar o envio; não há promessa de instantaneidade com app fechado. Uma
parada forçada pelo usuário só permite retomada depois de abrir o app. Com app fechado, mudanças feitas só no outro aparelho chegam na próxima
abertura/retomada ou sincronização manual. Em primeiro plano, o
[cliente ActionCable](android-live-invalidation.md) pede um ciclo quando chega
invalidação ou a assinatura é retomada. Não há polling periódico.

Room e WorkManager têm bancos separados. Se o processo morrer exatamente após
o commit e antes de persistir o agendamento, a lista e a fila ficam salvas,
mas o envio aguarda a próxima abertura. Falha no enqueue também preserva o
sucesso local e é reparada na abertura; não se informa falha de adição depois
de ela já ter sido salva. O backoff usa o mecanismo do WorkManager; este corte
não implementa um prazo específico por `Retry-After` (o ciclo atual não o
propaga no relatório).

## Validação

- JVM: política de retentativas, agendar somente após `Saved`, exclusão mútua e
  relatório compartilhado entre worker e tela; autorização só desperta o
  agendamento depois de guardar credencial e vínculo.
- `BackgroundSyncTest`: WorkManager real de teste + Room em arquivo, sem
  Activity; restrição de rede, recuperação, `KEEP`, sucessor durante um ciclo,
  backoff, recusa sem laço e recuperação após cancelamento da cadeia.
- `RailsSyncConvergenceTest.workerRetriesALostAnswerThroughRailsWithoutAnActivityOrASecondEffect`:
  duas invocações reais do worker com Room e HTTP contra Rails de teste; a
  primeira perde a resposta após commit e pede retentativa, a segunda reenvia
  os mesmos bytes/UUID, sem segundo efeito. Executar com
  `script/android_rails_sync_check`.

A validação física anterior da primeira fatia offline foi confirmada pelo
usuário em 11/11/2026. Isso não representa teste físico deste novo worker nem
substitui os testes específicos acima. Morte real do processo/reboot durante
um trabalho continua dependendo da garantia do WorkManager; os testes deste
corte controlam sua execução pelo TestDriver, sem reiniciar o sistema.

Referências: [trabalho único](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work),
[restrições e backoff](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work),
[versão utilizada](https://developer.android.com/jetpack/androidx/releases/work#2.10.1).

O corte Rails do [canal de invalidação](android-sync-invalidation.md) define o
endpoint nativo e publica revisões após commit. A assinatura Android em primeiro plano e seus gatilhos são descritos em
[Atualização nativa em primeiro plano](android-live-invalidation.md).
