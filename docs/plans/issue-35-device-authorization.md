# Execução da issue #35 — autorização de aparelhos

Planejado em 10/11/2026 para a [issue #35](https://github.com/isaaclvs/alva/issues/35),
parte da migração Android offline (#33). Não implementa autorização nesta etapa.

## Pré-condição e escopo

A dependência #34 está encerrada e os documentos estão na `main` consultada
(`83ed2de`). Antes de implementar, atualizar a `main` e criar uma branch curta.
Preservar a PWA e o APK/widget conectado; não alterar Kotlin, cookies ou o
ActionCable nesta entrega. Snapshot e processamento de comandos são tasks futuras.

Referências: [ADR 0001](../adr/0001-android-offline-com-rails.md),
[contrato v1](android-sync-contract-v1.md) e
[plano Android](android-offline.md).

## Base existente e lacunas

- `AndroidDevice` já pertence a uma casa, emite segredo aleatório, armazena
  SHA-256, tem `revoked_at` e compara `access_digest` com `HouseholdAccess.token`.
  Assim, trocar o hash da senha já invalida credenciais existentes.
- `POST /api/android/devices` pareia o widget e `POST /api/quick_add` inclui itens
  através da casa do token. Esses contratos precisam continuar funcionando.
- Não há escopo persistido nem barreira comum para a futura API v1. Também falta
  cobertura específica de isolamento, limitação de tentativas e vazamento.
- O pareamento atual limita 5 tentativas por 15 minutos por IP, em memória por
  processo. A nova rota não pode virar uma segunda cota para contornar o limite.

## Decisões de implementação propostas

1. Reutilizar `AndroidDevice`; adicionar `scope` obrigatório, com valores
   `add_only` e `sync`, validação e restrição no banco. Migração atribui
   `add_only` a todos os registros existentes e mantém esse default. Nunca
   promover token antigo ou aceitar escopo arbitrário vindo do cliente.
2. Manter a emissão legada explicitamente `add_only`. Nova emissão explícita em
   `POST /api/v1/devices`, mediante senha correta, gera outro registro `sync` e
   retorna `device_id` e `token` somente naquela resposta, com HTTP 201 e
   `Cache-Control: no-store`. Cookie da PWA ou token antigo não substituem senha.
   “Mostrado uma vez” significa retorno único na emissão; o Bearer é reutilizável.
3. Preservar segredo aleatório forte, digest e vínculo à versão da senha. Sem
   senha configurada, recusar pareamento de sync de forma controlada; o modo
   privado sem senha da PWA continua funcionando. Tratar também autenticação
   com credenciais antigas após remoção da configuração, sem erro 500.
4. Criar base de autenticação para controllers protegidos de `/api/v1`:
   parsing estrito de `Authorization: Bearer`, credencial válida → aparelho →
   casa autorizada → verificação de escopo. Ausente/inválida/revogada/rotacionada:
   401 `unauthorized`; válida `add_only`: 403 `forbidden_scope`.
   O controller de pareamento fica fora dessa exigência de Bearer.
5. A identidade vem exclusivamente do token. `household_id`, `device_id` e
   `scope` enviados pelo cliente nunca selecionam a casa ou elevam permissões.
   Recursos futuros devem ser buscados através da casa autenticada. A escolha
   da casa no pareamento continua sendo a casa configurada do produto doméstico,
   sem criar seleção pública de casas.
6. Manter `quick_add` aceitando `add_only`; permitir também `sync`, que inclui
   capacidade de adicionar. Não mudar JSON nem efeitos do endpoint legado.
7. Expor revogação individual idempotente no modelo e documentar procedimento
   via Rails console com ID do aparelho. Não criar painel administrativo nesta
   issue. Revogar um aparelho não afeta o outro; rotação invalida ambos os escopos.
8. Compartilhar a cota de pareamento entre rotas antiga e nova (5/15 min/IP),
   com armazenamento cujo comportamento no deploy esteja documentado e testado.
   Preferir store compartilhado compatível com o ambiente existente; validar
   contagem/expiração antes de escolhê-lo. Retornar 429 `rate_limited` em JSON
   na rota v1, incluindo orientação de retentativa. Não confiar em IP arbitrário
   de headers fora da configuração de proxies confiáveis do Rails.
9. Auditar filtragem de senha, token e Authorization em parâmetros/logs e
   caminhos de erro. O segredo só pode aparecer no corpo da emissão bem-sucedida;
   não devolver digests nem objetos ActiveRecord serializados. Respostas
   autenticadas v1 também usam `no-store`.

## Sequência e corte de entrega

Recomendação: **um PR para `main`**, com três commits coerentes e respectivos
testes. A issue é pequena o bastante para revisar emissão e barreira de acesso
juntas; não publicar uma API de sync sem proteção entre entregas.

1. **Modelo e migração:** escopos, backfill conservador, emissão explícita,
   autenticação e revogação; adaptar emissão antiga sem alterar seu contrato.
2. **HTTP:** pareamento v1, base protegida, rate limit compartilhado, erros,
   proteção de respostas e logs. Testar a base com controller/rota apenas de
   teste; não criar endpoint fictício de snapshot em produção.
3. **Integração e documentação:** completar matriz abaixo, documentar emissão,
   revogação e rotação; atualizar seção de autenticação do contrato v1 com o
   comportamento efetivamente validado e o limite adotado.

Cada commit mantém a suíte pertinente verde. Se houver necessidade concreta de
dividir em PRs, integrar primeiro modelo/migração compatíveis (`Refs #35`), e só
então criar a branch da entrega HTTP. Usar `Closes #35` apenas na entrega final.

## Matriz de validação

| Área | Evidência exigida |
| --- | --- |
| Migração | Registros existentes viram `add_only`, conservando digest, vínculo e revogação; valor inválido/nulo não persiste. |
| Emissão | Senha correta cria token `sync`; senha incorreta/ausente e cookie isolado não criam aparelho; digest difere do segredo; emissões são distintas. |
| Legado | Rota antiga emite apenas `add_only`, mesmo com `scope=sync`; token anterior ainda adiciona via `quick_add`. |
| Autenticação | Bearer ausente, vazio, malformado, desconhecido, revogado e anterior à rotação retornam 401, sem alteração de dados. |
| Escopos | `add_only` recebe 403 na base protegida v1; `sync` passa; ambos mantêm inclusão permitida. |
| Isolamento | Duas casas em fixtures: credencial A sempre resolve A; IDs de B no payload não alteram contexto e não permitem revogar B. Testar consultas de recursos nos endpoints reais quando forem criados. |
| Revogação | Revogar A bloqueia A e mantém B; repetir é seguro; rotação bloqueia tokens dos dois escopos e nova emissão funciona. |
| Rate limit | Até a cota, próxima tentativa dá 429; alternar rotas não contorna limite; IP distinto e fim da janela têm comportamento esperado. Isolar/limpar store entre testes. |
| Segredos | Resposta de sucesso contém apenas campos permitidos; erros não ecoam segredo; parâmetros e Authorization são filtrados nos caminhos de log utilizados. |
| Regressão | Login/cookie, ActionCable e fluxo atual de inclusão continuam passando; banco migrado aceita emissão antiga e nova. |

Rodar testes de model/request durante cada etapa e `bin/rails test` completo ao
final. Rodar `bin/rails test:system` se houver testes de sistema no repositório,
conforme AGENTS.md. Não criar testes de UI artificiais para uma mudança só de API.
Preservar CI existente e validar migração em banco temporário com linha legada,
sem acessar banco de produção.

## Critério de conclusão

Todos os critérios da #35 cobertos pela matriz, suíte e CI aprovados, diff sem
segredos, documentação alinhada e PR para `main`. A autorização dos endpoints
reais de snapshot/comandos será novamente testada nas respectivas tasks usando
a base protegida entregue aqui. Não considerar a sincronização implementada ao
encerrar esta issue.
