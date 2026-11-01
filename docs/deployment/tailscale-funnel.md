# Alva via Tailscale Funnel

O Funnel publica somente o serviço web em HTTPS no endereço Tailscale do Atlas.
SSH e administração continuam privados na tailnet. O cliente não precisa de
Tailscale no celular. O recurso é beta; mantenha a rota privada para manutenção.

## Proteção do app

- `ALVA_PASSWORD_DIGEST`: hash bcrypt da senha compartilhada da casa.
- `ALVA_PUBLIC_HOST`: hostname HTTPS, sem protocolo, porta ou barra.
- Com a senha configurada, páginas, exportação e mutações exigem acesso; o
  ActionCable também exige o cookie criptografado, inclusive com stream válido.
- O cookie dura 30 dias, é HttpOnly, SameSite=Lax e Secure em HTTPS.
- “Personalizar → Sair” remove o acesso deste navegador. Trocar o hash e reiniciar
  o app invalida os cookies anteriores. O reinício também fecha conexões antigas.
- Há limite de 10 tentativas a cada 3 minutos por IP, por processo do servidor.
  O limite em memória é reiniciado junto do processo.
- Manifesto, ícones, assets, service worker e `/up` não contêm a lista e continuam
  disponíveis sem login. Respostas privadas usam `Cache-Control: no-store`.
- Sem essas variáveis, a instalação privada mantém o comportamento anterior.
  Com `ALVA_PUBLIC_HOST`, a inicialização falha se o hash não for válido.

Não grave a senha ou o hash em arquivos versionados. Gere o hash usando bcrypt,
com entrada pelo terminal sem eco, e guarde-o em um arquivo privado de ambiente
do serviço (permissão 600). Use uma frase longa, exclusiva, com até 72 bytes.
Nunca passe a senha em argumentos de linha de comando.

## Implantação no Atlas

1. Mantenha o Funnel desligado durante a preparação. Preserve o estado de
   `tailscale serve status` para voltar à rota privada se necessário.
2. Faça backup consistente do SQLite, com a API de backup do SQLite ou com o
   serviço parado. Preserve todos os bancos em `storage/`.
3. Atualize o código e instale as dependências. Prepare os bancos, rode os seeds
   e compile os assets no ambiente de produção.
4. Configure o arquivo privado de ambiente e um drop-in systemd para o serviço
   `alva`, com `EnvironmentFile=/caminho/privado/access.env`. Conteúdo esperado:

   ```text
   ALVA_PUBLIC_HOST=atlas.taild80ca0.ts.net
   ALVA_PASSWORD_DIGEST=<hash bcrypt gerado localmente>
   ```

5. Recarregue systemd e reinicie o Alva. Acesse o endereço privado HTTPS:
   `https://atlas.taild80ca0.ts.net`. Confirme a tela de senha sem sessão e o
   funcionamento da lista após entrar. Confira o PDF e sincronização em dois
   aparelhos autenticados. HTTP direto redireciona a HTTPS no modo público.
6. Só depois publique o serviço:

   ```bash
   tailscale funnel --bg http://127.0.0.1:80
   tailscale funnel status
   ```

   O Tailscale pode pedir autorização do Funnel na conta. Autorize apenas o
   recurso necessário ao Atlas; não publique outras portas ou serviços.
7. Em um celular nos dados móveis, com Tailscale desligado, abra o endereço
   HTTPS. Verifique bloqueio sem senha, entrada, adicionar/marcar/desfazer,
   sincronização, PDF e instalação/reabertura da PWA. O hostname é o mesmo usado
   pelo Serve, mas navegadores/PWAs distintos podem ter cookies separados.

## Voltar ao acesso privado

No Atlas, desative o Funnel e restaure o Serve para o mesmo destino:

```bash
tailscale funnel --https=443 off
tailscale serve --bg http://127.0.0.1:80
tailscale serve status
```

Mantenha a senha configurada. A senha não depende do Funnel para funcionar.
Não altere o encaminhamento de portas do roteador.

## Referências

- https://tailscale.com/docs/features/tailscale-funnel
- https://tailscale.com/docs/reference/tailscale-cli/funnel
