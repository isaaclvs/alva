# Alva — marca

Símbolo: um check nascendo do horizonte (a "alva" é a primeira luz do dia),
com duas linhas abaixo que lembram uma lista.

- `mark.svg` — símbolo sozinho (viewBox 100×100). Na interface ele é o
  partial `app/views/application/_brand_mark.html.erb`: linhas em
  `currentColor`, check em `--color-accent`.
- `icon-maskable.svg` — fonte do ícone maskable do PWA (símbolo dentro da
  zona segura do Android).
- `public/icon.svg` — fonte do ícone padrão (quadrado arredondado).

Cores: fundo `#09090B`, linhas `#F4F4F5`, check `#2DD4BF`.

Regerar os PNGs depois de mudar os SVGs:

```bash
rsvg-convert -w 512 -h 512 public/icon.svg -o public/icon.png
rsvg-convert -w 192 -h 192 public/icon.svg -o public/icon-192.png
rsvg-convert -w 512 -h 512 docs/brand/icon-maskable.svg -o public/icon-maskable-512.png
```
