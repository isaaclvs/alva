# Modo de compras

## Objetivo
Na aba Lista, oferecer Iniciar compras para concentrar a interação em marcar e
 desmarcar produtos, sem controles de organização disputando atenção.

## Fluxo e desenho
- Lista normal conserva editar, remover, PDF, tema, navegação e Compra feita.
- Iniciar compras fica no cabeçalho da Lista, disponível com itens na lista viva
  (inclusive quando todos estão marcados). Não mostrar numa lista vazia.
- Ao iniciar, manter Lista aberta, fechar teclado e exibir um cabeçalho compacto
  com título Em compras, progresso/resumo e Sair das compras.
- Linhas mostram somente checkbox e nome, com área confortável para toque;
  categorias e itens comprados riscados permanecem visíveis.
- Ocultar editar, remover, exportar, toggle de tema, navegação inferior/lateral
  e atalhos para Adicionar. Usar o tema já escolhido.
- Compra feita continua disponível quando há comprados. Arquiva só os comprados,
  mantém pendentes e mostra Desfazer apenas no aparelho que agiu.
- Após Compra feita, continuar em modo de compras (permite comprar pendentes e
  desfazer sem saltar de contexto). Lista vazia apresenta mensagem simples e
  mantém Sair das compras disponível.
- Sair restaura a Lista normal, sem alterar status de item ou finalizar compra.
- Salvar preferência em cookie local separado do antigo market_mode, renderizar
  estado inicial no servidor para evitar flash de controles/teclado. Modo ativo
  força a tela Lista, inclusive em URLs de retorno/recarga.
- Bloquear retorno a Adicionar enquanto modo ativo; sempre oferecer saída.

## Implementação
1. Ler instruções do repositório e task Linear, se houver. Nenhuma task foi
   encontrada pela consulta atual; este plano e pedido do usuário são o escopo.
2. Acrescentar estado local de compras ao shell/Stimulus, sem persistir na casa
   nem transmitir preferência por ActionCable. Preservar cookie screen/tema.
3. Usar atributo no ancestral e CSS para ocultar controles nas linhas e nos
   fragments novos. Broadcasts não devem ler cookies nem restaurar controles.
4. Tratar edição já aberta: não permitir entrar no modo com formulário de
   correção visível (ocultar/desabilitar início até salvar/cancelar, com indicação
   clara), evitando descartar rascunho ou deixar edição exposta no checklist.
5. Adaptar desktop/tablet/mobile: retirar espaço da navegação oculta, manter
   leitura confortável e ações finais acessíveis; ambos temas.
6. Testes significativos: entrar/sair sem alterar dados; checklist funciona;
   controles ocultos inclusive após broadcast; preferência e tela Lista após
   reload; outro aparelho independente; compra/undo e lista vazia; rascunho de
   edição; desktop e mobile sem overflow. Manter testes existentes.
7. Executar bin/rails test e bin/rails test:system em sequência (mesmo SQLite),
   RuboCop e git diff --check. Relatar resultado.

## Entrega
Agente implementa e deixa commits na branch isolada, sem push/PR/merge. Agente
principal revisa o diff, testa integração, corrige achados e só então integra,
commita se necessário e envia direto à main, conforme autorização do usuário.
