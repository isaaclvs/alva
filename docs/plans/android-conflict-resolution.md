# Resolução local — #45, descarte auditado

O serviço Room `ConflictResolver` revê e retira da fila dois tipos de grupo
(`Kind`), sempre por decisão explícita da pessoa no painel "Revisar
alterações" da lista (`ConflictPanel`, sem modal, fora do formulário de
adicionar):

- `REFUSED` — recusa definitiva do servidor: **Descartar**.
- `UNCERTAIN` — tentativa sem resposta que nunca poderá ser respondida
  (`idempotency_conflict`, credencial antiga perdida): **Usar a lista do
  servidor**. Ver "Tentativa incerta" abaixo.

Depois de retirar um grupo, a tela pede um ciclo de sincronização. A #45
continua aberta até reaplicação com UUIDs novos, fila legada já tentada e
envelopes grandes serem tratados.

`preview(rootId)` lê a fila numa transação e devolve as intenções em português,
IDs do grupo e token de revisão. `discard(preview)` revalida o grupo inteiro em
outra transação. Uma alteração de comandos, subjects, nomes mostrados, revisão
confirmada ou vínculo torna a confirmação antiga inválida (`Stale`). Não se
pode confirmar apenas o UUID de um comando sem rever o grupo atual.

Só pode iniciar descarte uma raiz `CONFLICT`/`REJECTED` que foi enviada com
bytes congelados e recebeu recusa definitiva para todas as tentativas.
Inclui todos os dependentes transitivos, preservando os independentes. Um
comando consumido, um recibo existente, uma tentativa sem resposta, um comando
legado tentado sem bytes ou `idempotency_conflict` em qualquer integrante
protege o grupo inteiro. A restrição também é conferida no commit do descarte.
Dependentes nunca enviados podem acompanhar o descarte explicitamente revisto.

A decisão não desfaz um efeito remoto: só retira intenções que o servidor
recusou ou que nunca foram enviadas. Não inventa `ACKNOWLEDGED`, não reemite
comando e não libera tentativas incertas para instalar snapshot.

## Tentativa incerta (credencial perdida)

Se o segredo da linhagem antiga se perdeu (Keystore apagado, reinstalação com
a fila preservada), o aparelho pareia de novo com uma linhagem nova e todo
comando antigo reenviado recebe `rejected`/`idempotency_conflict`. Essa
resposta não fecha a tentativa: o servidor dá o mesmo erro com ou sem efeito
anterior, então o comando segura o snapshot para sempre (`SnapshotHeld`).

Pelo contrato v1, o cliente decide: sincronizar o snapshot, ver o que de fato
foi aplicado e reemitir o que faltar. Aqui a decisão é da pessoa, nunca
deduzida pelo nome. Raiz elegível: `REJECTED` + `idempotency_conflict`, com
bytes congelados e tentativa enviada. O grupo leva **todos** os dependentes
transitivos, mesmo os incertos ou legados — aceitar a lista do servidor já é
desistir de saber o efeito de tudo que dependia da raiz. Um dependente
`ACKNOWLEDGED` ou com recibo protege o grupo (prova de efeito que não pode
sumir). A auditoria e a revalidação (`Stale`, conferência no commit) são as
mesmas do descarte, com o tipo do grupo no token de revisão.

Retirado o grupo, nenhum comando incerto dele segura o snapshot; o próximo
ciclo instala a lista do servidor, que mostra o que aconteceu (o item aparece
uma vez se o servidor o aplicou e some se não). A tela pede para conferir a
lista e adicionar de novo o que faltar; a reemissão automática com UUIDs
novos ainda não existe. Outras tentativas sem resposta continuam segurando o
snapshot normalmente.

## Persistência e atomicidade

Room **6**, migração aditiva **5→6**: tabela independente
`command_resolutions` com UUID da decisão, instante local, JSON dos comandos
originais e IDs dos subjects. Preserva sequência, UUID, payload/preconditions
como strings exatas, documento wire como bytes textuais exatos, dependências,
revisão observada, status/erro, tentativas/respostas, instante e subjects com
papel/ordem/categoria. Campos nulos são explícitos. Sem chave estrangeira para
a fila: a auditoria sobrevive à retirada das linhas originais.

Auditar → retirar grupo → reprojetar a lista é uma única transação Room. Falha
em qualquer passo reverte todos; uma abertura posterior encontra a mesma fila
ou a decisão completa. Nenhum dado confirmado, alias ou histórico é alterado.
Schemas 1–5 permanecem intactos. Não há limpeza automática da auditoria.
O banco v6 exige APK que conheça v6; não tentar downgrade destrutivo.

## Validação

Testes Room em arquivo verificam fechamento transitivo, independente preservado,
auditoria após reabrir, bloqueio por tentativas incertas/legadas/consumidas,
preview obsoleto e falha durante reprojeção revertendo auditoria/fila/lista.
Migração 5→6 compara todas as tabelas antigas e conserva bytes/tentativas;
a cadeia completa desde v1 é validada pelo teste de migrações existente.

```sh
cd android
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.ts.taild80ca0.atlas.twa.data.ConflictResolverTest,net.ts.taild80ca0.atlas.twa.data.AlvaDatabaseMigrationTest
./gradlew testDebugUnitTest --tests net.ts.taild80ca0.atlas.twa.data.AlvaMigrationsTest
```

`RailsSyncConvergenceTest.aLostCredentialLeavesAnUncertainAddThatThePersonReleasesToTheServersList`
(via `script/android_rails_sync_check`) prova o caso incerto contra Rails real:
resposta perdida após o commit, troca de linhagem, `idempotency_conflict`
repetido, liberação explícita e convergência com o item uma única vez.

Próximos cortes: reaplicar intenções com UUIDs novos após snapshot fresco,
fila legada já tentada sem bytes e divisão de `ENVELOPE_TOO_LARGE`.
