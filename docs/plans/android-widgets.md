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

Formatos de referência: 4×2 e 4×3. Mostra pendentes na ordem das categorias do app, com conteúdo rolável.
Cada linha traz nome em destaque, categoria secundária e checkbox arredondado. Tocar no nome abre a Lista sem teclado; o
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


## Redesenho aprovado em 12/11/2026 — versão 2.0.2

A referência aprovada substitui o layout inicial: widgets compactos, hierarquia
clara e botões reais com áreas próprias. Adicionar rápido usa um botão circular
à esquerda; a lista mostra marca, ações, título/contador e categorias abaixo dos
nomes; os atalhos usam cartões tonais, símbolo separado e posição estável.
O espaço efetivo e a escala de fonte determinam quantos cartões cabem.

A configuração agora usa Compose, com áreas seguras de status/navegação,
prévia visual de 2×2 e 4×2, campo de nome, chips de habituais, até oito escolhas,
reordenação recolhível e um único botão principal de salvar. A prévia de tamanho
não redimensiona o widget: o tamanho real continua sendo escolhido no launcher.
As configurações e componentes existentes são preservados na atualização.

Para conferir o desenho nativo sem servidor ou credenciais reais, a variante
**debug** tem uma fixture em `WidgetDesignPreviewActivity`, acessível pelo link
`alva-dev://widget-design?screen=widgets` ou `?screen=config`. Parâmetros
`dark=true` e `color=teal` permitem conferir as outras paletas. A fixture usa as
RemoteViews e a tela Compose reais com dados de exemplo em memória; fica fora
da release. Reinicie o processo do Alva Dev após essa inspeção para voltar ao
fluxo normal. Não use a fixture nos celulares de uso diário.
