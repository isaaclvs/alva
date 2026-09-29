# CLAUDE.md

Contexto do projeto, decisões de produto, stack e convenções de código estão
em **AGENTS.md**, na raiz do repositório. Leia-o antes de qualquer trabalho
neste projeto — este arquivo só complementa com instruções específicas de
workflow para o Claude Code.

## Como trabalhar neste repositório

- O trabalho é rastreado no Linear, time **SHO**. Antes de implementar, busque
  a task correspondente (ex.: "SHO-2") e leia contexto, critério de aceite e
  notas técnicas — não implemente a partir de suposição quando a task já
  documenta o escopo.
- Uma task = um escopo fechado. Não adiante trabalho de tasks futuras (ex.:
  não implemente real-time via ActionCable enquanto ainda estiver em SHO-5,
  que é sequencial e não real-time — isso é SHO-6).
- Ao concluir uma task, rode a suíte de testes (`bin/rails test` e, se houver
  testes de sistema, `bin/rails test:system`) antes de dar como pronta.
- Sempre que uma task tiver checklist de critério de aceite, valide item a
  item antes de reportar conclusão.

## Convenções de commit

- Commits pequenos e no escopo de uma task por vez.
- Mensagem de commit em português, no imperativo, referenciando o identificador
  da task do Linear quando existir (ex.: `Adiciona service de categorização automática (SHO-4)`).
- Não fazer commit de `config/master.key`, credenciais ou artefatos de build
  (node_modules, cache do PWA, etc.) — confirmar que `.gitignore` cobre isso.

## Rodando o projeto localmente

```bash
bin/rails server        # sobe o servidor Rails local
bin/rails test           # suíte de testes
bin/rails test:system    # testes de sistema (Capybara)
bin/rails db:seed        # popula categorias e dicionário de itens (idempotente)
```

## PWA

App é instalável direto do browser ("adicionar à tela inicial"), sem app nativo
por enquanto — decisão tomada na SHO-1 depois de avaliar Ruby Native (SaaS pago)
e Hotwire Native (toolchain Android pesada pra meta atual de teste rápido).
Manifest e service worker vêm do gerador padrão do Rails (`rails/pwa`), rotas em
`config/routes.rb` e views em `app/views/pwa/`.

Nota de infra: service worker só registra em `https:` ou `localhost`. Testar
local funciona sem HTTPS; para self-hosted via Tailscale (SHO-9/SHO-10) vai
precisar HTTPS (Tailscale tem certificado automático via `tailscale serve`).
