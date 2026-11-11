# Resolução local — #45, descarte auditado

Este corte entrega o serviço Room `ConflictResolver`; ainda não acrescenta
botões à tela. A #45 continua aberta até a interface, reaplicação, tentativas
incertas e envelopes grandes serem tratados.

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

Próximos cortes: ligar preview/decisão à interface, reaplicar intenções com
UUIDs novos após snapshot fresco e resolver o estado incerto sem deduzir pelo
nome que a ação foi ou não executada. Este serviço não resolve esses casos.
