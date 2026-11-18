# Alva — rosa claro

A paleta rosa clara usa canvas quase branco, superfícies brancas e acentos rosa. As cores semânticas ficam em `app/assets/tailwind/pink_light.css`; cores dos ícones de categoria permanecem independentes.

| Token | Valor |
| --- | --- |
| canvas | `#fcf8fa` |
| surface | `#ffffff` |
| surface-2 | `#f6edf2` |
| line | `#eadde4` |
| ink | `#30242c` |
| ink-soft | `#66535e` |
| ink-faint | `#786570` |
| accent | `#b73570` |
| accent-strong | `#922955` |
| accent-ink | `#ffffff` |
| accent-soft | `#f8e8f0` |
| warn | `#805000` |
| danger | `#a32636` |

## Contraste medido

Razões calculadas pela fórmula WCAG 2.x para luminância relativa sRGB. Texto normal requer 4.5:1; texto grande e elementos gráficos requerem 3:1.

| Par | Contraste |
| --- | ---: |
| ink / canvas | 14.1:1 |
| ink / surface | 14.9:1 |
| ink-soft / canvas | 6.7:1 |
| ink-soft / surface | 7.1:1 |
| ink-soft / surface-2 (chip "Outros") | 6.2:1 |
| ink-faint / canvas | 5.1:1 |
| ink-faint / surface-2 (contador) | 4.7:1 |
| branco / accent (botão primário) | 5.6:1 |
| branco / accent-strong (estado forte) | 7.9:1 |
| warn / canvas | 6.5:1 |
| danger / canvas | 6.9:1 |
| accent / accent-soft | 4.7:1 |
| line / surface (limite visual) | 1.3:1 |

`line` serve como separador sutil, não como única indicação de controle ou estado. `ink`, `ink-soft` e `ink-faint` formam três níveis de texto distintos, e todo par texto/fundo usado nas duas telas fica em pelo menos 4.5:1 — `test/theme_contrast_test.rb` percorre esses pares nos quatro temas (escuro/claro × teal/rosa) e falha se algum cair abaixo disso.
