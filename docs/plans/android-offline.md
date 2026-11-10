# Alva Android offline — passo 1

Data: 10/11/2026. Kotlin confirmado pelo usuário; regras de sincronização
propostas para validação na implementação. Sem mudanças de execução nesta etapa.
Decisão: [ADR 0001](../adr/0001-android-offline-com-rails.md).
Acompanhamento solicitado pelo usuário no GitHub:
[Alva nativo offline — #33](https://github.com/isaaclvs/alva/issues/33).
Contrato de sincronização: [android-sync-contract-v1.md](android-sync-contract-v1.md).
Inventário de paridade: [android-parity-inventory.md](android-parity-inventory.md).

## Fluxo de entrega

Branches curtas saem da `main` atualizada e PRs voltam para `main`, sem staging.
Commits frequentes representam mudanças pequenas e coerentes. Cada PR entrega
um comportamento revisável e compatível com a versão em uso. Uma task pode ter
mais de um PR; usar `Refs` nos intermediários e `Closes` apenas na conclusão.
Dependências precisam estar integradas à `main` antes do trabalho dependente,
sem empilhar branches de funcionalidades. A issue principal mantém a sequência,
as dependências e os critérios de aceite de cada task.

## Escopo

Uma casa, dois aparelhos, experiência atual preservada: Adicionar e Lista,
categorias, habituais, comprar de novo, edição, remoção, desfazer e modo compras.
Widget, telas e ações principais devem funcionar após configuração inicial,
mesmo sem internet ou com Atlas indisponível. Compartilhar alterações exige que
os aparelhos consigam falar com o servidor; não há sincronização direta entre
celulares offline.

Ficam fora: cadastro público, múltiplas casas pela interface, cobrança,
assinaturas, iOS e novas funcionalidades comerciais. Exportação PDF precisa
ser incluída no inventário de paridade antes da transição; não pode desaparecer
silenciosamente por depender hoje do servidor.

## Stack e organização

- Kotlin e Jetpack Compose para as telas Android, preservando cores e fluxos.
- Room sobre SQLite: lista, habituais, categorias, dicionário e dados necessários
  a comprar de novo; estado confirmado e operações locais ainda não confirmadas.
- Coroutines/Flow para observar o banco; DataStore para preferências do aparelho.
- WorkManager para retentativas persistentes com conexão disponível. Android
  pode adiar execução em segundo plano: não prometer atualização instantânea
  com app fechado. Ao abrir/retomar, sincronizar imediatamente se possível.
- HTTPS/JSON versionado com Rails, que continua com SQLite e regras centrais.
- Um módulo Gradle de aplicativo inicialmente, organizado por responsabilidade,
  sem arquitetura de múltiplos módulos ou framework de injeção obrigatório.
- Widget: reutilizar provider/layout e painel de adicionar existentes, trocando
  o envio online por gravação local. Glance pode ser adotado se houver benefício
  concreto; não é necessário reescrever o widget para usar Compose nas telas.
- Manter minSdk atual (24) inicialmente; conferir compatibilidade das versões
  estáveis escolhidas no início da implementação, sem fixar versões neste plano.

O módulo de lista expõe observar estado e executar intenções (adicionar,
definir comprado/pendente, corrigir, remover, finalizar, desfazer). Telas e widget
usam a mesma interface. Persistência, fila e reconciliação ficam dentro da
implementação, sem decisões de rede nas telas.

## Acesso sem login cotidiano

Conectar à casa uma vez, online, usando a senha compartilhada que já existe.
Sem e-mail, conta individual ou nova tela a cada abertura. QR code fica como
melhoria posterior, não dependência da primeira versão.

Emitir credencial própria para sincronização completa, com escopo explícito,
digest no servidor, revogação e armazenamento protegido por Android Keystore.
A credencial antiga de inclusão continua limitada. Não embutir senha no APK.
Rotação de senha mantém a revogação atual; dados locais não são apagados e
ações pendentes ficam preservadas até reconectar à mesma casa.

Primeira autorização/carga requer rede. Depois, a cópia local funciona sem ela.
Não misturar dados de casas ao reautorizar. Não restaurar credenciais cegamente
por backup Android; troca/reinstalação exige autorização e carga do servidor.
Operações que nunca saíram do aparelho não podem ser recuperadas do servidor
após desinstalação/perda física. Documentar e testar política de backup local.

## Persistência e sincronização

1. Cada ação grava sua alteração visível e um comando com UUID na mesma
   transação local. Só então limpar o campo/mostrar sucesso local.
2. Fila ordenada por aparelho, sem expiração silenciosa. Retentar o mesmo UUID
   após falha de rede; não criar uma segunda ação.
3. Rails processa sob transação/lock da casa e guarda o resultado por UUID,
   ligado à casa, aparelho e conteúdo. Reenvio devolve o mesmo resultado;
   UUID reaproveitado com conteúdo diferente é rejeitado.
4. IDs públicos gerados offline e aliases para itens unificados permitem que
   comandos posteriores alcancem o item correto. Manter IDs Rails existentes.
5. Servidor retorna resultados e snapshot consistente com revisão monotônica
   da casa. Inicialmente preferir snapshot completo do conjunto sincronizado,
   adequado ao volume doméstico; não construir log incremental sem necessidade.
6. Cliente atualiza estado confirmado e reaplica comandos locais ainda pendentes
   em transação. Snapshot antigo nunca substitui revisão mais nova; uma leitura
   remota não apaga a fila.
7. No app aberto, sinal de mudança pelo ActionCable dispara nova sincronização;
   payload é dado/invalidação, não HTML Turbo. Reconexão e retorno ao app sempre
   verificam o servidor. WorkManager cobre retentativas em segundo plano.
8. Alterações feitas pela PWA também incrementam a revisão compartilhada,
   inclusive operações em lote que hoje usam update_all e remoções. Não
   depender somente de callbacks por item nem do updated_at como cursor.

Manter UUIDs de operações e exclusões enquanto puderem ser reenviados. Não
eliminar metadados de deduplicação com um prazo arbitrário que permita duplicar
uma compra após longo período offline. Fila de operações, não event sourcing.

## Regras propostas de concorrência

Não usar relógio do celular para escolher quem ganha. Serializar no servidor e
usar revisões/precondições. Operações causalmente dependentes do mesmo aparelho
referenciam a operação anterior; não enviar uma sequência inteira com a mesma
versão antiga. Conflito bloqueia os dependentes, não ações independentes.

| Situação | Resultado proposto |
| --- | --- |
| Dois aparelhos adicionam o mesmo nome | Um pendente canônico; ambos recebem seu ID, sem duplicata. |
| Nome comprado é digitado novamente | Revive pela regra atual, desde que não tenha sido alterado/arquivado desde a observação local; conflito não revive uma compra nova silenciosamente. |
| Marcar/desmarcar reenviado | Enviar estado desejado, nunca toggle. Reenvio da operação não inverte estado. |
| Duas marcações para o mesmo estado | Já estar no estado desejado é sucesso sem nova transição, se ainda na lista. |
| Marcar versus desmarcar sobre revisão antiga | Primeira mudança válida vence; outra recebe conflito, sem sobrescrita silenciosa. |
| Edição ou remoção de item que mudou | Preservar rascunho/intenção local e apresentar estado atual para revisão; não sobrescrever nem remover uma versão desconhecida. |
| Ação sobre item removido ou arquivado | Não recriar implicitamente; só ação explícita de adicionar/desfazer pode restaurar. |
| Compra feita offline | Enviar IDs e versões dos comprados vistos nesse celular; nunca arquivar todos os comprados que existirem ao receber a requisição. |
| Um item dessa compra mudou enquanto isso | Finalização atômica: se alguma precondição falhar, não arquivar parcialmente; mostrar itens a revisar e preservar o registro local da intenção. |
| Duas finalizações sobre os mesmos itens | Primeiro comando válido arquiva; repetição do mesmo UUID devolve resultado; outro UUID não cria viagem vazia/duplicada. |
| Desfazer remoção | Compensar a operação identificada, sem duplicar pendente nem sobrescrever alterações posteriores. |
| Desfazer compra | Só a viagem mais recente, como hoje, e com precondições; rejeição mantém os dados e explica o motivo. |
| Habituais concorrentes | Unicidade por nome normalizado; remover um habitual não altera a lista ou histórico. |

Desfazer continua disponível por cerca de 5 segundos no aparelho que agiu.
Também funciona offline como comando compensatório ordenado. O servidor não
usa o token web de 1 minuto para validar operações que aguardaram rede.

Exemplo: Ana conclui Leite e Pão sem sinal. Isaac adiciona Café e marca Ovos.
Ao sincronizar a conclusão, Café e Ovos ficam na lista, pois não estavam entre
os itens enviados. Se Isaac desmarcou o mesmo Leite antes, a conclusão recebe
conflito em vez de arquivar algo que agora está pendente.

## Categorização offline e interface

Sincronizar dicionário e categorias. Centralizar normalização/categorização em
um módulo Kotlin usado por telas e widget; compartilhar exemplos de teste com
TextNormalizer/ItemCategorizer Ruby, incluindo acentos e plurais. Servidor é a
autoridade de duplicados e resolve divergências sem perder o nome digitado.
Definir transliteração equivalente usando fixtures; não presumir que as duas
bibliotecas de Unicode produzem resultados idênticos.

Categoria escolhida manualmente offline vale localmente e gera aprendizado ao
sincronizar. Aprendizado concorrente usa precondição de revisão do dicionário;
conflito preserva a correção do item e informa que o aprendizado precisa ser
revisto. Sem escolha manual, reconciliação pode atualizar a categoria automática
segundo o dicionário atual do servidor. Esses casos exigem testes próprios.

Preferências de tema, cor, tela e modo compras são locais, como hoje. Retorno
de adicionar e desfazer pertence só ao aparelho que agiu. Mostrar discretamente
“Salvo neste aparelho”/“Aguardando sincronização”; falhas permanentes e conflitos
exigem aviso persistente com recuperação, sem modal no fluxo normal de adicionar.

## Validação obrigatória nas próximas etapas

- Salvar offline, matar processo, reiniciar celular e reencontrar dados/fila.
- Perder resposta depois do commit no servidor e reenviar sem duplicar efeitos.
- Adicionar e finalizar simultaneamente em dois clientes; comandos fora de
  ordem, pendências dependentes, conflitos, exclusões e desfazer tardio.
- Snapshot desatualizado não apagar edição local ou ressuscitar removidos.
- PWA e Android convergirem, inclusive em finalização com update_all.
- Widget salvar com app fechado/sem rede pelo mesmo módulo de lista.
- Revogação preservar dados e permitir reautorização sem duplicar comandos.
- Upgrade do APK atual preservar identidade, widget e dados autorizados.
- Testes de normalização compartilhados e migrações Room não destrutivas.
- Suíte Rails completa e testes de sistema conforme mudanças; Android com
  testes de banco/fila, integração e validação nos dois aparelhos reais.

Meta inicial a medir em release nos aparelhos reais: retorno local ao adicionar
ou marcar em até 100 ms e lista utilizável em até 1 segundo numa abertura fria,
sem aguardar rede. Metas propostas, não medições ou garantias já verificadas.

## Encerramento do passo 1 e próxima entrega

Este documento registra a escolha de Kotlin e as regras propostas para revisão.
Não muda o APK ou servidor atual. A implementação começa pelo contrato
versionado, autorização completa, idempotência e snapshot no Rails, seguido de
uma fatia Android de adicionar/marcar offline e sincronizar em dois aparelhos.
Widget existente só migra para a fila após essa base estar validada.

ALV-15 é histórica e explicitamente exclui reescrita/offline; não alterar seu
escopo retroativamente. A migração é acompanhada pela issue #33 no GitHub e
suas tasks, por solicitação do usuário; ALV-8 registra a concorrência atual.
