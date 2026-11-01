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

Deploys do Rails atualizam o conteúdo aberto pelo app. Alterações do domínio,
metadados Android ou recursos do invólucro podem exigir um novo APK. O app
continua dependente de internet, Atlas ligado e navegador Android compatível.

Verificação feita: assinatura JAR/v1, certificado correspondente ao assetlinks,
integridade dos arquivos assinados e download por HTTPS sem sessão. O teste
de instalação e abertura em um Android físico deve ser feito pelo usuário.
