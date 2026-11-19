# APK nativo do Alva

A versão 2.0.6 (código 11) é o cliente Android nativo online, em Kotlin/Compose.
Instala como atualização das versões TWA 1.x: mantém o identificador
`net.ts.taild80ca0.atlas.twa` e a chave original de assinatura. Na primeira
abertura do cliente nativo, informe a senha da casa uma vez; o app e o widget
compartilham a credencial protegida pelo Android Keystore.

O servidor continua sendo a fonte da verdade. A release não inclui Room,
fila local ou WorkManager. Sem conexão, uma ação falha com mensagem e conserva
o texto para nova tentativa. A PWA continua disponível no mesmo endereço.

## Fontes e variantes

- `src/online`: telas, temas/fontes, preferências DataStore, acesso, API v1 e painel
  rápido, compartilhados pelas duas variantes.
- `src/release`: composição de produção sem banco local e configuração TLS.
- `src/debug`: implementação offline pausada e infraestrutura dos testes, sem
  ligação com a interface online. Instala como `Alva Dev`, com identificador
  terminado em `.debug` e FileProvider separado.
- `src/twa`: painel Java legado preservado como referência, fora da compilação.

O widget Adicionar rápido conserva o mesmo componente de Activity,
para preservar os PendingIntents dos widgets instalados. Os três widgets
(Adicionar rápido, Lista de compras e Atalhos de casa) acompanham tema e cor do
app. O painel nativo aceita
vários nomes, mantém rascunho em falhas e recriações, bloqueia envios repetidos
e confirma/fecha após 1,5 s. Sem acesso, oferece abrir o app principal.

## Build e assinatura

Use JDK 21, Android SDK/plataforma 36 e build-tools 36.0.0. Configure
`ANDROID_HOME` ou `android/local.properties` (ignorado pelo Git).

A chave original e seus dados estão em
`~/.local/share/alva/android-signing/alva-1.0-package.zip`, com acesso restrito.
Nunca versione ou publique a chave ou suas senhas. Configure somente no ambiente
local do build: `ALVA_ANDROID_KEYSTORE`, `ALVA_ANDROID_STORE_PASSWORD`,
`ALVA_ANDROID_KEY_ALIAS` e `ALVA_ANDROID_KEY_PASSWORD`.

Para publicar uma versão, use `script/android_release` (`--dry-run` ensaia):
ele constrói, assina, confere o certificado contra `assetlinks.json` e escreve o
APK, o `.sha256` e o `latest.json` em `public/downloads/`. O passo a passo,
a instalação por cima da versão anterior e o backup cifrado da chave estão em
`docs/deployment/android-release.md`.

```sh
cd android
./gradlew testDebugUnitTest verifyVariants
./gradlew assembleRelease
```

Sem essas variáveis, a release é unsigned e serve apenas para CI. Com assinatura,
a saída é `android/app/build/outputs/apk/release/app-release.apk`. A produção usa
`https://atlas.taild80ca0.ts.net`; somente a debug aceita `-PalvaServerUrl` para
testes locais. `verifyVariants` confere launcher nativo, backup/credenciais e
que a release não resolve dependências de Room ou WorkManager.

O certificado SHA-256 deve continuar igual ao de `public/.well-known/assetlinks.json`:
`51:3D:D0:F0:B8:21:DD:09:80:54:02:9C:EE:70:35:DC:B3:AD:D1:48:03:0C:6F:69:5D:3A:02:FA:6F:6B:13:21`.

## Versão publicada

A 2.0.6 (código 11) está em `/downloads`: `Alva-2.0.6.apk`, o `.sha256` e o
`latest.json` (SHA-256 `1610f085e944d01ba91a901423858490933b6b47ad3a8b5d113b4cac6cc9502f`).
Em 19/11/2026 o usuário aprovou o app nativo nos dois celulares (#111), que
passaram a usar só ele. Os APKs TWA 1.x continuam em `/downloads` como
histórico, e a PWA continua disponível no mesmo endereço, para quem não usa o
APK.
