# Alva nativo online — paridade com a PWA

Acompanhamento: **#92** (uma sub-issue por entrega, com dependências).

Decisão do usuário em 11/11/2026: o app Android nativo passa a ser **online**,
igual à PWA, com **as mesmas funcionalidades, temas, cores e fontes**. Offline
volta a ser discutido só depois que o app nativo estiver redondo no uso diário
do casal. Até lá, o Alva 1.3 (TWA) continua sendo o app de uso.

## Princípios

- **O servidor é a fonte da verdade, como na PWA.** Cada ação vai ao Rails e a
  tela mostra o que ele responde. Sem fila local, sem projeção, sem resolução
  de conflito no aparelho.
- **Sem rede, o app avisa e não perde o que foi digitado**, como a PWA faz hoje
  (texto fica no campo, ação pode ser repetida). Nada é guardado para envio
  posterior.
- **Paridade visual medida, não estimada.** Cada tela é comparada lado a lado
  com a PWA (mesmo tema, cor e lista), por captura de tela no emulador.
- **Reaproveitar a API v1 que já existe** (`/api/v1/devices`, `/commands`,
  `/snapshot`, `/cable`). Ela já é idempotente: reenviar após falha de rede não
  duplica. Quando o offline voltar, o cliente já fala o protocolo certo.
- **Um comportamento por PR**, da `main`, com testes. A PWA e o Alva 1.3
  continuam funcionando durante todo o trabalho.

## Arquitetura do cliente online

```
Tela Compose ──ação──▶ OnlineRepository ──POST /commands──▶ Rails
     ▲                       │  (UUID por ação; retenta só falha de rede,
     │                       │   com o mesmo corpo)
     └──── estado ◀── GET /snapshot ◀── após cada ação / ao abrir / sinal do ActionCable
```

- `OnlineRepository`: guarda em memória o último snapshot (e uma cópia em
  disco só para abrir rápido, sempre substituída pelo servidor), envia um
  comando por ação e espera a resposta antes de atualizar a tela.
- Erros viram mensagens em português, como na PWA (sem códigos).
- O código offline atual (Room, fila, reconciliação, painel de revisão) **fica
  no repositório, desligado da interface**, para ser retomado depois. A release
  não leva Room.

## Mapa de paridade

| Funcionalidade da PWA | Rails hoje | Falta no Rails | Fase |
| --- | --- | --- | --- |
| Entrar com a senha da casa | pareamento `/api/v1/devices` | — | 0 (#95) |
| Temas escuro/claro, cores teal/rosa, Nunito Sans | — (tokens no CSS) | — | 0 (#93) |
| Tempo real entre os dois celulares | ActionCable `/api/v1/cable` | — | 0 (#94) |
| Adicionar (vírgula/linha, até 30, dedup) | `add_items` | — | 1 (#97) |
| Dica de duplicado ao digitar | catálogo no snapshot | — | 1 (#97) |
| "Acabou de adicionar" com troca de categoria | `add_items` + `correct_item` | — | 1 (#97) |
| Categorias com contagem | snapshot | — | 1 (#98) |
| Marcar/desmarcar | `set_item_state` | — | 1 (#98) |
| Desfazer marcação (toast) | — | comando `undo_item_state` (#96) | 1 (#98) |
| Remover (botão e swipe) + desfazer | `remove_item`, `undo_remove` | — | 1 (#99) |
| Corrigir nome/categoria, aprender categoria | `correct_item` | — | 1 (#100) |
| Busca e "Só pendentes" | snapshot | — | 2 (#101) |
| Iniciar compras (checklist, progresso, tela acesa, vibração) | snapshot | — | 2 (#102) |
| Compra feita + desfazer compra | — | comandos `finish_shopping`, `undo_shopping` (#51) | 2 (#103) |
| Habituais (criar, remover, adicionar selecionados/todos) | snapshot traz a coleção | comandos de habituais (#104) | 3 (#105) |
| Comprar de novo (até 5 atalhos) | `BuyAgainSuggestions` (HTML) | campo aditivo no snapshot (#106) | 3 (#107) |
| Exportar PDF | `/lista.pdf` com cookie | PDF com token na API v1 (#108) | 3 (#109) |
| Menu ⋯ (tema, cor, PDF, sair) e tela aberta lembrada | cookies | — (DataStore no aparelho) | 3 (#109) |
| Widget "Adicionar" e painel rápido | `/api/quick_add` | — | 4 (#110) |

## Fases

**Fase 0 — Fundação (sem funcionalidade nova visível)**
1. Tema Compose a partir dos tokens de `app/assets/tailwind/application.css`:
   2 temas × 2 cores, Nunito Sans nos 3 pesos; comparação visual com a PWA.
2. `OnlineRepository` (snapshot + comando + ActionCable) e a tela atual
   ligada a ele em vez do Room; erro de rede sem perder o texto.
3. Tela de entrada com a senha da casa no visual da PWA.

O item 1 é a #93, o 2 é a #94 e o 3 é a #95. A #93 e a #94 não dependem
uma da outra e podem começar em paralelo; a Rails #96 também.

**Fase 1 — Lista do dia a dia** (o mínimo para trocar o Alva 1.3)
Adicionar completo, dica de duplicado, "acabou de adicionar", categorias,
marcar/desmarcar e desfazer (Rails: `undo_item_state`), remover com swipe e
desfazer, corrigir nome/categoria.

**Fase 2 — Compras**
Busca, "Só pendentes", modo compras (checklist, progresso, tela acesa,
vibração), compra feita e desfazer compra (Rails: #51).

**Fase 3 — Restante da PWA**
Habituais (Rails: #53), comprar de novo, PDF, menu ⋯ com tema/cor/sair,
lembrar a tela aberta.

**Fase 4 — Troca do app da casa**
Widget e painel rápido nativos (#110); a variante **release** deixa de ser TWA e passa
a ser o app nativo, com o **mesmo identificador e a mesma chave** do Alva 1.3:
instala por cima, como atualização (versão 2.0.0, código 5). Na primeira
abertura pede a senha da casa uma vez. Validação nos dois celulares antes de
publicar o APK em `/downloads` (#111).

Cada fase termina com o APK debug ("Alva Dev") instalável nos dois celulares
para conferir; só a fase 4 substitui o app de uso.

## Rastreamento

- **#92** acompanha esta migração; sub-issues #93–#111 e a #51 (Rails,
  finalizar e desfazer compras), movida do #33 porque serve também ao online.
- O offline (#33 e as filhas abertas) está com o rótulo `pausado`; nada foi
  fechado. A #53 continua no #33; a parte de Rails dos habituais para o online
  é a #104.
- `AGENTS.md` ("Produto atual e destino nativo") registra esta decisão.

## Fora de escopo agora

Offline, fila local, resolução de conflitos, histórico além do que a PWA
mostra, monetização, contas públicas, iOS.
