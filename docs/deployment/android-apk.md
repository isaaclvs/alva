# APK nativo do Alva

A versão 2.0.0 (código 5) é o cliente Android nativo online, em Kotlin/Compose.
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

O widget conserva a aparência rosa do Alva 1.3 e o mesmo componente de Activity,
para preservar os PendingIntents dos widgets instalados. O painel nativo aceita
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

## Entrega 2.0.0

O APK assinado para avaliação está em `tmp/releases/Alva-2.0.0.apk` (não versionado).
Instale por cima do Alva existente, sem desinstalar. O teste físico de atualização,
o widget e o aceite de paridade nos dois celulares continuam sendo a etapa do
usuário na #111. A publicação padrão em `/downloads` fica para depois desse aceite;
o download antigo não é substituído por este build de avaliação.

Validação deste artefato: assinatura v2 e certificado conferidos contra o APK
original e o assetlinks; instalação por cima do APK público 1.0 no emulador
concluída sem desinstalar; abertura nativa confirmada. A compatibilidade física
com o Alva 1.3 e o aceite nos dois aparelhos ainda precisam ser conferidos.
SHA-256: `857a561f512d4390403647a8c8ae8d68a26c61ff1985a664cc5d0b21aa1efdbc`.
