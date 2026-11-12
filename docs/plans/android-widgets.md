# Widgets do Alva nativo online

Desenho aprovado em 12/11/2026: três widgets independentes no seletor do
Android. O usuário pode instalar mais de uma instância, combinando os usos.
O servidor continua sendo a fonte da verdade; nenhuma ação fica em fila.

## Adicionar rápido

Uma superfície de toque abre o painel rápido existente com campo focado e
teclado pronto. Formatos de referência: 2×1 e 4×1, redimensionáveis conforme o
launcher. Não mostra habituais nem a lista. O componente existente é preservado
para que widgets instalados continuem funcionando após a atualização.

O painel aceita vários nomes com as regras do app, bloqueia envio repetido,
conserva o rascunho na falha e fecha após confirmação breve do servidor.
Sem acesso, oferece abrir o app para conectar a casa.

## Lista de compras

Formatos de referência: 4×2 e 4×3. Mostra pendentes por categoria, na ordem
do app, com conteúdo rolável. Tocar no nome abre a Lista sem teclado; o
checkbox envia a marcação e oferece Desfazer após sucesso. O envio usa a
versão observada no widget, para não sobrescrever uma alteração não vista.

Cabeçalho com contador e atualização manual; acesso à lista e ao painel de
adicionar. Edição, remoção e finalização de compra continuam no app.

## Atalhos de casa

Grade de habituais escolhidos explicitamente, com formatos de referência 2×2
e 4×2. Cada instância tem título, seleção e ordem próprios: por exemplo,
“Cozinha” e “Limpeza”. Configurar não modifica os habituais da casa.

Cada toque adiciona um único habitual. Os atalhos mantêm a posição e indicam
quando o item está pendente. Item comprado pode voltar pela regra existente
do servidor. Remover um habitual não o substitui automaticamente por outro.

## Regras comuns

- Tema claro/escuro e cor teal/rosa acompanham as preferências do aparelho.
- Texto escalável, alvos de toque confortáveis e descrições acessíveis;
  estados são expressos em texto, além da cor.
- Sucesso só após confirmação do servidor; falha permite nova tentativa
  explícita, sem guardar comandos para enviar mais tarde.
- Atualizações após ações no app ou widgets, leitura do servidor e comando
  manual. Atualização periódica é complementar e depende do Android; não há
  garantia de tempo real com o app fechado.
- Última leitura eventualmente mantida serve apenas à apresentação, com
  indicação de desatualização. Perda de acesso oculta dados da casa.
- Reutilizar `OnlineRepository` e as ações existentes, sem Room ou WorkManager
  na release e sem ampliar o offline pausado.

As dimensões são referências de grade; o tamanho efetivo depende do launcher,
do espaço disponível e da escala de fonte.
