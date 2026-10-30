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

## Cor por aparelho

O controle “Personalizar”, ao lado do toggle claro/escuro, permite escolher
Teal (padrão) ou Rosa. A escolha usa o cookie `color`, por um ano, independente
do cookie `theme`, e não é transmitida aos outros aparelhos. O HTML inicial
já recebe `data-color`, evitando uma troca de paleta durante o carregamento.

O rosa usa carvão rosado no escuro e branco levemente rosado no claro; os
ícones de categoria mantêm suas cores. A marca dentro do app acompanha o acento.
Os arquivos estáticos do ícone instalado continuam com a identidade original.
A paleta clara e os contrastes estão documentados em `pink-light.md`.
