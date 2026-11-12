# Nunito Sans

Source: Google Fonts, Nunito Sans (version 19), weights 400, 600 and 700.
License: SIL Open Font License 1.1; see OFL.txt.

The WOFF2 files in app/assets/fonts are subsets of the upstream TrueType
files, generated with fontTools and Brotli. They retain U+0020–00FF,
U+2000–206F, the euro and trademark symbols, left/right arrows and minus.
This includes Portuguese accents. The app loads these files locally using
font-display: swap; no Google Fonts request is made by the app.

## Android

The native debug app (android/app/src/debug/res/font) packages the same three
weights as TrueType (nunito_sans_regular.ttf, nunito_sans_semibold.ttf and
nunito_sans_bold.ttf), because Android cannot read WOFF2. They are the very
same subsets as the WOFF2 files above, with only the Brotli wrapper removed:
`TTFont("nunito-sans-<weight>.woff2")`, `flavor = None`, `save("….ttf")`
(fontTools 4.68 with Brotli). Glyphs, metrics and the SIL OFL 1.1 license are
unchanged, so the app and the PWA render the same letters.
