# Lista de Compras

App de lista de compras compartilhada, uso doméstico, self-hosted. Rails
tradicional (MVC + views), instalável como PWA direto do browser. Contexto de
produto completo em `AGENTS.md`.

## Rodando o servidor Rails localmente

Requisitos: Ruby na versão de `.ruby-version`, SQLite3.

```bash
bin/setup           # bundle install + prepara o banco (idempotente)
bin/rails server    # sobe o servidor em http://localhost:3000
```

Outros comandos úteis:

```bash
bin/rails test           # suíte de testes (model/request/controller)
bin/rails test:system    # testes de sistema (Capybara)
bin/rails db:seed        # popula categorias e dicionário de itens
```

## Testando como PWA (instalar no celular)

1. Suba o servidor (`bin/rails server`) numa máquina acessível pela rede local
   (ou já com Tailscale configurado — ver SHO-9/SHO-10).
2. Abra a URL no navegador do celular (Chrome/Android ou Safari/iOS).
3. Use "Adicionar à tela inicial" (menu do navegador). O app abre em tela
   cheia, com ícone próprio, como um app instalado.

Sem passo de build — é só abrir a URL. Não tem modo offline (decisão de
produto); o service worker existe só pra satisfazer o critério de
instalabilidade do navegador.

**Nota:** o registro do service worker só funciona em `https:` ou
`localhost`. Testar na mesma máquina funciona sem HTTPS; testar por IP da
rede local ou por Tailscale (`http://`) funciona pra navegar, mas o navegador
pode não oferecer "adicionar à tela inicial" até o servidor ter HTTPS
(Tailscale resolve isso com certificado automático via `tailscale serve`,
ver SHO-9/SHO-10).

## Por que PWA (e não app nativo)

A ideia original (SHO-1) era empacotar o app como nativo Android. Avaliamos
duas opções e descartamos as duas por ora:

- **Ruby Native**: SaaS pago (rubynative.com, plano Starter US$299/ano),
  precisa de conta na plataforma, e o fluxo de build/deploy é orientado a
  publicação nas lojas — incompatível com "sem conta, self-hosted" já
  decidido pro projeto.
- **Hotwire Native**: open source, mas exige toolchain Android completa
  (Android Studio, SDK, Gradle, emulador) só pra empacotar uma WebView.

Pra meta atual — ter algo testável rápido com o usuário real — PWA entrega o
essencial (ícone próprio, tela cheia, "instalado" no celular) sem toolchain
nenhuma. App nativo fica em aberto pro futuro, se fizer sentido depois do
teste.
