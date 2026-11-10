# Telas nativas locais — #43

Implementadas somente no APK debug, depois da configuração da casa. A release
continua TWA. `MainActivity` apresenta `ShoppingScreens`, com as cores escuras e
o destaque turquesa do Alva atual.

## Fluxo

- Adicionar abre com foco e teclado, aceita vários nomes via `AddText` e mostra
  a dica de duplicados do módulo compartilhado. O campo fica perto do rodapé,
  acima da navegação e do teclado.
- Somente `Saved` permite limpar o texto submetido. Um novo rascunho digitado
  enquanto o commit acontece não é apagado. Falha, entrada vazia e duplicados
  mantêm o texto; nenhuma exceção interna aparece na tela.
- O retorno “Acabou de adicionar” pertence à ação local, não a emissões do banco.
  Por enquanto nome/categoria são somente leitura; a correção vem na #48.
- Lista agrupa categorias pela ordem recebida e mostra os comprados riscados,
  contador de pendentes e caixas com alvo de toque de pelo menos 48 dp.
  Marcar envia estado desejado, com uma operação em voo por item.
- As duas telas observam `ShoppingListStore`, sem chamadas HTTP. A mensagem
  de salvamento distingue gravação neste aparelho de sincronização pendente.
  A verificação de conexão fica no menu do cabeçalho, sem ocupar o rodapé.
- Texto e tela aberta sobrevivem à recriação da Activity via `rememberSaveable`.
  Preferências duráveis entre novas sessões são da #55. Lista/fila sobrevivem
  à reabertura do processo porque são lidas do Room.

## Limites

Não envia a fila nem aplica snapshots (#45). Não há promessa de convergência
entre aparelhos nesta entrega. Antes da primeira carga do dicionário, nomes
ficam no grupo Outros, sem inventar UUID de categoria. Não inclui edição,
desfazer, habituais, comprar de novo, modo compras ou widget; essas tarefas
seguem no acompanhamento #33.

## Validação

`ShoppingScreensTest` usa a Activity instalada e um banco Room em arquivo.
O transporte falha se for chamado: testa inclusão múltipla, marcação e
reabertura do banco, duplicados sem enqueue, falha sem limpar rascunho,
digitação durante commit, rotação e atualização por outro consumidor do store.
`MainActivityAccessTest` continua testando configuração e reconexão com as
novas telas no lugar do placeholder.

No host local de 8 GB, executar testes de browser e emulador em sequência.
Serializar o Android com `flock --close -w 600 /tmp/alva-android-validation.lock`:
o `--close` impede que o servidor ADB herde o lock e o retenha após o teste.
