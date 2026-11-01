# Desempenho do Alva

## Método

Benchmark no PC de desenvolvimento, Rails em modo test, SQLite temporário separado dos bancos do app, adapter de broadcast de teste. Três aquecimentos e 20 amostras por operação/cenário. Tempos medidos do request completo no servidor, incluindo callbacks, renderização e broadcast; sem rede ou renderização do navegador. Consultas incluem operações não atendidas pelo query cache.

Dados sintéticos: 8 categorias, 50 ou 200 itens vivos e 0 ou 5.000 registros arquivados, estes distribuídos por 100 nomes em uma viagem sintética. Esse histórico testa processamento de registros, não representa a frequência de compras da casa. A adição temporária é removida fora da medição; marcações alternam o estado.

Os JSONs guardam medianas e p95. `gzip_response_bytes` estima a compressão do HTML capturado com Zlib; a compressão real foi verificada em requests com Accept-Encoding: gzip. A medição de CPU usa Accept-Encoding ausente nos dois lados, para não misturar a mudança do payload com o trabalho do render.

Reprodução (nenhum dado da casa é alterado):

```bash
RAILS_ENV=test bin/rails runner script/benchmark_list
```

O baseline foi medido no código anterior às otimizações, após `ca62d21`, com a mesma rotina, exceto a coluna adicional de bytes gzip e a instrumentação do novo tipo de refresh no resultado final.

## Resultados sintéticos

| Itens | Histórico | Ação | Antes mediana (ms) | Depois mediana (ms) | Depois p95 (ms) |
|---:|---:|---|---:|---:|---:|
| 50 | 0 | Abrir | 23.09 | 22.62 | 29.32 |
| 50 | 0 | Adicionar | 48.55 | 45.33 | 48.4 |
| 50 | 0 | Marcar/desmarcar | 44.28 | 13.7 | 15.45 |
| 50 | 5000 | Abrir | 168.46 | 33.3 | 34.62 |
| 50 | 5000 | Adicionar | 264.6 | 56.39 | 73.98 |
| 50 | 5000 | Marcar/desmarcar | 199.48 | 13.89 | 15.54 |
| 200 | 5000 | Abrir | 191.84 | 83.93 | 119.36 |
| 200 | 5000 | Adicionar | 375.54 | 159.13 | 193.54 |
| 200 | 5000 | Marcar/desmarcar | 308.72 | 33.53 | 34.97 |

As marcações atualizam só a categoria afetada, resumo, contador e finalização. Adicionar, corrigir, remover, arquivar e recuperar conexão mantêm o snapshot completo. Rascunhos de correção são preservados também quando a categoria é substituída. O histórico é agregado no SQLite, sem carregar e normalizar todos os registros em Ruby; as buscas de duplicados usam a coluna normalizada e seu índice existente. Nenhum cache compartilhado de HTML/sessões foi introduzido.

## Rede e Atlas

`network-before.json`: 10 conexões novas por rota à página de entrada. `network-keepalive.json`: uma conexão HTTP/1.1 reutilizada, com 10 requests após o primeiro. São medições deste PC, não de rede móvel. Os endereços públicos foram fixados a partir do DNS público, evitando que o MagicDNS redirecionasse a medição para a tailnet.

No Atlas, a página de entrada respondeu em cerca de 6 ms via loopback. Pelo Funnel, a primeira conexão levou aproximadamente 1,0–1,2 s, e requests em conexão reutilizada cerca de 270–277 ms. Esse custo de rede não desaparece com otimização de SQL.

`atlas-before.json` e `atlas-after.json` comparam apenas GETs autenticados à lista real e ao refresh, 10 amostras após 3 aquecimentos. Tempo é o X-Runtime informado pelo Rails, separado da rede; bytes são os efetivamente recebidos, negociando gzip nos dois casos. Nenhum item real foi criado, marcado ou removido. A compressão e o número de bytes não equivalem ao tempo até a tela ficar interativa.

## Validação

309 testes de unidade/integração e 93 de sistema passaram, incluindo dois aparelhos, filtro, foco e rascunhos durante atualizações parciais. RuboCop e Brakeman limpos. Nenhuma alteração de banco ou novo APK é necessária.
