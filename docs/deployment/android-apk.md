# APK do Alva

O pacote Android usa Trusted Web Activity e abre https://atlas.taild80ca0.ts.net/.
Foi gerado pelo PWABuilder, com Bubblewrap, em 31/10/2026.

- Identificador: `net.ts.taild80ca0.atlas.twa`.
- Versão inicial: `1.0.0.0`, código `1`.
- Associação pública: `public/.well-known/assetlinks.json`, acessível sem login.
- MIME do download: `application/vnd.android.package-archive`.
- Download no Atlas: `/downloads/Alva-1.0.apk` (artefato não versionado).
- SHA-256 do APK: `4ae8736554702d067aa04a00ddcb42abec5c5b9aab7f8349aa28423d64fe9948`.

O pacote completo com chave, informações de assinatura e fonte Android está
armazenado no PC de desenvolvimento em `~/.local/share/alva/android-signing/`,
com acesso restrito. Preserve um backup privado desse diretório. Nunca envie
chaves ou senhas para o Git. Atualizações do APK devem reutilizar a mesma chave
e incrementar o código de versão; recriar a chave impede atualizar a instalação
existente. A chave de assinatura é independente da senha de entrada da casa.

## Widget Adicionar

A versão `1.2` refina o widget rosa com marca discreta, convite para adicionar e
atalho circular de inclusão. A versão `1.3` mantém esse widget e passa a abrir
um painel rosa na parte inferior da tela (opção A, "Ao alcance"), acima do
teclado. O envio bloqueia toques repetidos, confirma o resultado e fecha a janela
após 1,5 segundo. Fechar pelo × cancela apenas a janela; não envia produtos.
No primeiro uso, a pessoa informa a senha da casa uma vez; o app
guarda uma credencial limitada à inclusão, criptografada pelo Android Keystore.
O widget precisa de internet e do Alva disponível. Se o envio falhar, o texto
fica na janela para nova tentativa.

A fonte Android versionada está em `android/`. O módulo compila com JDK 21
(AGP 8.9.1, Gradle 8.11.1, Kotlin 2.1.21 com o plugin do Compose) e o Android
SDK com a plataforma 36 e as build-tools 36.0.0. Aponte o SDK pelo
`ANDROID_HOME` ou por `android/local.properties` (`sdk.dir=...`, ignorado pelo
Git). O Gradle confere o checksum da distribuição do wrapper.

## Desenvolvimento (variante debug)

A variante debug é o ponto de entrada nativo (Kotlin/Compose) usado para
construir o cliente offline. Ela instala ao lado do aplicativo da casa:

- Identificador `net.ts.taild80ca0.atlas.twa.debug`, nome "Alva Dev" e
  `FileProvider` próprio, sem colisão com o APK de produção.
- Abre a tela Compose nativa; o Trusted Web Activity fica desabilitado.
- A variante release continua abrindo a TWA pela `LauncherActivity`.

Comandos, a partir de `android/`:

```sh
./gradlew assembleDebug        # APK debug instalável
./gradlew testDebugUnitTest    # testes unitários da variante debug
./gradlew lintDebug            # lint da variante debug
./gradlew verifyVariants       # confere os manifestos efetivos de debug e release e que a release não tem Room
./gradlew connectedDebugAndroidTest  # testes Room instrumentados (exige emulador/aparelho)
```

A persistência local (Room, fila de comandos) só existe na debug por enquanto:
ver [android-local-persistence.md](../plans/android-local-persistence.md).

Instalação em um aparelho autorizado no `adb`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Artefatos: `android/app/build/outputs/apk/debug/app-debug.apk`, relatório de
lint em `android/app/build/reports/lint-results-debug.html` e testes em
`android/app/build/reports/tests/testDebugUnitTest/`. O CI reproduz esses
comandos e ainda compila a variante release sem chave.

## Release (assinatura local)

A assinatura é externa ao Git. Para gerar um APK release, use um JDK 21 e o
Android SDK. Configure `ALVA_ANDROID_KEYSTORE`,
`ALVA_ANDROID_STORE_PASSWORD`, `ALVA_ANDROID_KEY_ALIAS` e
`ALVA_ANDROID_KEY_PASSWORD` somente no ambiente local de build e rode
`./gradlew assembleRelease` dentro de `android/`. Nunca coloque os valores ou a
keystore no repositório. O arquivo de saída é
`android/app/build/outputs/apk/release/app-release.apk`. Sem as variáveis de
assinatura o build gera `app-release-unsigned.apk` apenas para validar a
variante; esse arquivo não serve para instalar ou atualizar o app da casa.

Deploys do Rails atualizam o conteúdo aberto pelo app. Alterações do domínio,
metadados Android ou recursos do invólucro podem exigir um novo APK. O app
continua dependente de internet, Atlas ligado e navegador Android compatível.

Verificação feita: assinatura JAR/v1, certificado correspondente ao assetlinks,
integridade dos arquivos assinados e download por HTTPS sem sessão. O teste
de instalação e abertura em um Android físico deve ser feito pelo usuário.
