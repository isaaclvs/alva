# Release do APK e guarda da chave de assinatura

Procedimento para publicar uma versão do app nativo e para manter uma cópia da
chave de assinatura fora do notebook de desenvolvimento. O contexto do app, das
variantes e da paridade está em `docs/deployment/android-apk.md`.

A chave é o ativo mais frágil do projeto: o Android só aceita uma atualização
assinada pela mesma chave, e o `assetlinks.json` fixa o certificado para a TWA.
Perder a chave, ou trocá-la, obriga a desinstalar o app (perdendo as preferências
do aparelho) e a republicar o `assetlinks.json`.

## Antes de começar

- JDK 21, Android SDK com a plataforma 36 e build-tools 36.0.0; `ANDROID_HOME`
  configurado ou `android/local.properties` preenchido.
- `keytool`, `java`, `apksigner`, `aapt`, `ruby` e `sha256sum` (ou `shasum`) no
  `PATH`; o script procura `apksigner` e `aapt` no build-tools mais novo do SDK
  quando não estão no `PATH`.
- A chave de release fora do repositório, com a senha e o alias à mão. O script
  recusa um keystore ou um arquivo de senha dentro do repositório.

## Env de assinatura

As quatro variáveis vêm do ambiente e nunca do repositório:

```text
ALVA_ANDROID_KEYSTORE    caminho do keystore (.jks), fora do repositório
ALVA_ANDROID_KEY_ALIAS   alias da chave de release
ALVA_ANDROID_STORE_PASSWORD   senha do keystore
ALVA_ANDROID_KEY_PASSWORD     senha da chave
```

Cada uma aceita um par `<nome>_FILE` apontando para um arquivo privado (0600)
cuja primeira linha é o valor — por exemplo `ALVA_ANDROID_STORE_PASSWORD_FILE` e
`ALVA_ANDROID_KEY_PASSWORD_FILE`. Use essa forma: assim a senha não fica no
histórico do shell. Um arquivo de senha dentro do repositório é recusado.

O script nunca imprime o caminho do keystore, o alias nem as senhas, e não usa
`set -x`. Ao assinar, os valores vão para o Gradle pelo ambiente do build, nunca
como argumento de `gradlew`, e a senha chega ao `keytool` por um arquivo
temporário 0600 — não pela linha de comando, onde `ps` a mostraria.

Exemplo de sessão de release, com os arquivos privados fora do repositório:

```sh
export ALVA_ANDROID_KEYSTORE="$HOME/.config/alva/release.jks"
export ALVA_ANDROID_KEY_ALIAS_FILE="$HOME/.config/alva/key-alias"
export ALVA_ANDROID_STORE_PASSWORD_FILE="$HOME/.config/alva/store-password"
export ALVA_ANDROID_KEY_PASSWORD_FILE="$HOME/.config/alva/key-password"
```

## Versão

`versionCode` e `versionName` vivem em `android/app/build.gradle` e são
alterados à mão, de propósito: o repositório é a fonte do que foi publicado. O
`versionCode` sempre cresce; o `versionName` acompanha a versão anunciada
(`2.0.4` → `2.0.5`). Não edite esses valores durante um ensaio: o script
compara o `versionCode` do código com o último `latest.json` publicado.

## Passo a passo do release

1. Confira o código que vai sair: `main` atualizada, CI verde, nada pendente de
   outra entrega.
2. Ajuste `versionCode` e `versionName` em `android/app/build.gradle` e faça o
   commit — o que é publicado precisa existir no repositório.
3. Ensaie sem construir, assinar ou escrever nada:

   ```sh
   script/android_release --dry-run
   ```

   O ensaio lê a configuração, valida as ferramentas, confere o certificado do
   keystore e o `versionCode` contra o publicado, e mostra o que faria. Ele não
   cria o diretório de saída.

4. Rode o release de verdade:

   ```sh
   script/android_release
   ```

   O script constrói `assembleRelease`, roda `apksigner verify --print-certs` e
   aborta se o certificado não for o que o `assetlinks.json` fixa — um APK
   assinado por outra chave não atualizaria o app instalado. Depois confere o
   pacote, o `versionCode` e o `versionName` declarados dentro do APK contra o
   `build.gradle`, e só então escreve os artefatos.

5. Artefatos em `public/downloads/` (ignorados pelo Git):

   - `Alva-<versão>.apk`
   - `Alva-<versão>.apk.sha256` — formato `sha256sum`, para `sha256sum -c`
   - `latest.json` — `versionName`, `versionCode`, `sha256` e `publishedAt`

   O APK é o último arquivo a aparecer com o nome final, e o `latest.json` só
   avança depois que os arquivos que ele descreve já estão no lugar: uma
   interrupção no meio nunca deixa um download apontando para um APK pela metade.

6. Confira o resultado:

   ```sh
   cat public/downloads/latest.json
   cd public/downloads && sha256sum -c Alva-<versão>.apk.sha256
   ```

7. Instale no aparelho e valide o comportamento (próxima seção). A publicação é
   local: nada é enviado a lugar nenhum.

`ALVA_RELEASE_DIR` e `ALVA_RELEASE_LATEST_JSON` existem para um ensaio ponta a
ponta em outro diretório, sem tocar no que está publicado. O `latest.json`
anterior é a referência do `versionCode`: se o diretório já tem um APK e o
`latest.json` não está lá, o script se recusa a publicar por cima sem saber a
versão anterior.

## Instalar por cima do app anterior

O identificador (`net.taild80ca0.atlas.twa` — grafia histórica do pacote) e a
chave são os mesmos desde a TWA 1.x, então a versão nativa instala por cima,
como atualização. Nunca desinstale para instalar de novo: desinstalar apaga as
preferências do aparelho, e o widget perde os `PendingIntents` registrados.

Com o aparelho conectado e depuração USB ativa:

```sh
adb install -r public/downloads/Alva-<versão>.apk
```

- Sem `-r` o Android recusa a instalação por cima; com `-r` ele preserva dados.
- Se o `adb install` falhar com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, o APK é de
  outra chave ou de outro identificador. Não desinstale: descubra qual build é.
- Se falhar com `INSTALL_FAILED_VERSION_DOWNGRADE`, o `versionCode` não cresceu.

Depois de instalar, confira no aparelho:

1. O app abre já com a casa configurada, sem pedir a senha outra vez (a
   credencial fica no Android Keystore e sobrevive à atualização). Se pedir, é
   sinal de que a instalação perdeu os dados — pare e investigue antes de seguir.
2. A lista, as categorias e os habituais continuam lá, iguais aos do servidor.
3. O widget **Adicionar rápido** continua na tela inicial e adiciona um item; o
   widget de **Lista de compras** e os **Atalhos de casa** abrem o app.
4. Adicionar, marcar, desmarcar, corrigir e "Compra feita" funcionam, e um
   segundo aparelho recebe as mudanças.

Uma atualização de verdade é a única prova de que a chave publicada é a chave
certa: um APK com assinatura diferente só falha aqui, no aparelho.

## Backup cifrado da chave

A chave hoje tem uma única cópia, no notebook. O objetivo é ter pelo menos duas
cópias cifradas, em lugares diferentes, e saber restaurá-las.

### O que guardar

- O keystore (`.jks`) — a chave privada em si.
- O alias e as duas senhas. Sem eles o keystore é inútil; com eles qualquer
  pessoa assina em nome do Alva.
- O certificado público exportado (`keytool -exportcert`), que não é segredo e
  serve para conferir a restauração.

Não há como recuperar a chave se o keystore e as senhas se perderem juntos, e um
backup só do keystore é tão inútil quanto nenhum.

### Cifrar

Use uma frase longa (uma frase inteira, não uma palavra) e diferente da senha do
keystore. Cifre simetricamente, com `gpg`:

```sh
gpg --symmetric --cipher-algo AES256 \
  --output alva-release-$(date +%Y%m%d).tar.gz.gpg \
  alva-release.tar.gz
```

Monte o `alva-release.tar.gz` com o keystore, o alias e as senhas em arquivos
separados e legíveis só pelo dono, e apague o arquivo `.tar.gz` em texto claro
assim que a cópia cifrada estiver conferida. O keystore em uso continua onde
está; o que não pode sobrar é uma cópia desprotegida dele:

```sh
umask 077
tar czf alva-release.tar.gz -C "$HOME/.config/alva" release.jks key-alias \
  store-password key-password
gpg --symmetric --cipher-algo AES256 --output alva-release.tar.gz.gpg alva-release.tar.gz
shred -u alva-release.tar.gz   # ou rm -f quando não houver shred
```

`age -p` (com frase) e `openssl enc -aes-256-cbc -pbkdf2` também servem; o que
importa é cifrar com frase, nunca copiar o keystore em texto claro para nuvem ou
pendrive. Para conferir que o arquivo cifrado é o que se espera antes de
distribuir, decifre para um diretório temporário e compare o SHA-256 com o do
keystore original (`sha256sum`).

### Onde guardar

Duas cópias cifradas, em mídias e locais diferentes, nenhuma delas no notebook
nem em pasta sincronizada que o notebook acessa automaticamente:

- um pendrive guardado fora de casa (gaveta, cofre, casa de alguém de confiança);
- um armazenamento em nuvem, enviado do pendrive ou de outra máquina — de
  preferência com segundo fator na conta.

Guarde a frase em um gerenciador de senhas (ou em papel, junto do pendrive) que
não seja o mesmo lugar da cópia cifrada: quem achar o pendrive não deve achar a
frase. Anote também o alias e o nome do arquivo original do certificado — são
pistas de recuperação, não segredos.

Reveja os backups uma vez por ano e a cada troca de aparelho: um backup nunca
testado não é um backup.

### Restaurar

O teste de restauração é o mesmo procedimento de uma recuperação real; faça-o em
um diretório temporário, nunca no lugar do keystore em uso:

1. Decifre a cópia em um diretório temporário com permissão restrita
   (`umask 077`, diretório em `$TMPDIR`).
2. Confira o SHA-256 do keystore decifrado contra o valor que acompanha o
   backup, e o certificado contra o que o `assetlinks.json` fixa:

   ```sh
   keytool -list -v -keystore /caminho/temporario/release.jks -alias <alias> \
     -storepass:file /caminho/temporario/store-password | grep SHA256
   ```

   O digest precisa ser
   `51:3D:D0:F0:B8:21:DD:09:80:54:02:9C:EE:70:35:DC:B3:AD:D1:48:03:0C:6F:69:5D:3A:02:FA:6F:6B:13:21`.

3. Aponte as variáveis para a cópia restaurada e rode o ensaio do release:

   ```sh
   script/android_release --dry-run
   ```

   O script confere o certificado do keystore restaurado contra o
   `assetlinks.json`; se ele aceitar, aquela chave ainda assina atualizações.

4. Apague o diretório temporário (`shred -u` nos arquivos, se disponível) e
   limpe as variáveis da sessão do shell.

Com a cópia restaurada validada, o release é o procedimento normal: ele assina
com a chave recuperada e o APK instala por cima do app que está no aparelho.
