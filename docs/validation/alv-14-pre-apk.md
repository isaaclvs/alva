# ALV-14 — validação pré-APK em dois Androids

**Estado em 21/10/2026:** roteiro preparado; aparelhos físicos, versão Android/Chrome e evidências ainda pendentes. Nenhum teste físico é declarado como concluído.

**Checagem desta sessão:** `adb devices -l` não listou aparelhos conectados. O Tailscale local estava ativo e o peer Atlas aparecia online. Uma tentativa HTTPS para uma URL candidata falhou ao conectar à porta 443 (`curl` código 7); a URL publicada/rota efetiva ainda não foi confirmada. Esse resultado não permite concluir que o app esteja fora do ar ou que a URL candidata seja a rota correta.

## Antes de começar

Use dois Androids reais (A e B), ambos atualizados e conectados ao mesmo tailnet, e a URL HTTPS real da Alva. Registre o commit/versão implantado, modelo e versão Android/Chrome de cada aparelho. Faça a rodada numa lista de teste descartável ou num ambiente de homologação; a Alva não tem autenticação e a lista é compartilhada. Não exponha nomes, IDs do tailnet ou dados domésticos em capturas públicas.

Confirme com quem administra o servidor qual URL e versão devem ser avaliadas. O repositório ainda tem `192.168.0.1` como host de exemplo em `config/deploy.yml`; a seção `proxy.ssl` está comentada. `config.assume_ssl` e `config.force_ssl` também estão comentados em `config/environments/production.rb`. Os testes de proxy exercitam cabeçalhos encaminhados, mas isso não comprova a configuração ativa do Atlas.

## Roteiro

1. **HTTPS, PWA e conexão em tempo real.** Em ambos os telefones, abra a URL informada pelo administrador. Confirme que o navegador mostra conexão segura, sem aviso de certificado, e que a Alva carrega. Instale/abra a PWA pelo Chrome nos dois. No aparelho A, adicione um item de teste; confirme que B o recebe sem recarregar a página. Repita de B para A. Se houver depuração remota disponível, capture no painel Network a conexão `/cable` com `wss://` e resposta `101`; registre também qualquer reconexão ou erro. A atualização visível entre aparelhos é o teste funcional da transmissão.
2. **Teclado e foco.** Abra a Alva recém-carregada fora do modo mercado: o campo de adicionar deve receber foco e permitir digitação imediata. Adicione um item e confirme que o campo continua pronto para o próximo. Toque em controles da lista e confira que o teclado aparece apenas ao editar texto. Ative o modo mercado, feche a PWA e abra de novo: os controles de entrada devem continuar ocultos e o teclado não deve abrir sozinho.
3. **Lista longa e atualizações concorrentes.** Em A, adicione 30 nomes de teste variados (o limite documentado do campo), incluindo nomes que caiam em categorias diferentes. Percorra do topo ao fim e confira rolagem, categorias e itens comprados ainda visíveis. Deixe o campo focado em A enquanto B adiciona outro item; confirme em A que a lista atualiza sem perder o foco nem o texto que estiver sendo digitado. Anote travamentos, saltos de rolagem ou linhas duplicadas.
4. **Segundo plano e tela bloqueada.** Com a lista aberta em A, bloqueie a tela ou deixe a PWA em segundo plano por dois minutos. Em B, adicione e marque um item como comprado. Volte a A e aguarde a reconexão; confirme que o estado mais recente aparece sem recarregar manualmente. Repita deixando B bloqueado e A ativo. Isto avalia retorno de conexão, não uso offline.
5. **Modo mercado e habituais.** Ative modo mercado só em A e confirme que B continua no modo normal; em A, a lista e as ações de compra continuam acessíveis enquanto formulário, Comprar de novo e Habituais ficam ocultos. Saia do modo e, no painel Habituais, crie um nome, adicione-o à lista e remova-o dos habituais. Confira que a remoção do atalho não apaga o item já adicionado e que atualizações feitas pelo outro telefone não fecham o painel nem limpam seleções ainda válidas.
6. **Desfazer remoção e compra.** Remova um item pendente em A. B deve receber a remoção, mas não o toast de desfazer. Use Desfazer em A dentro de aproximadamente cinco segundos; o item deve voltar uma vez. Marque outro item como comprado e toque em “Compra feita”. Confirme que somente os comprados saem da lista viva, os pendentes permanecem e B recebe a mudança. Use o desfazer da compra em A e confirme que a viagem é revertida e os itens voltam. Registre qualquer falha de atualização ou duplicação.
7. **Persistência.** Após a rodada, recarregue a página nos dois aparelhos e confirme o mesmo estado da lista. Feche completamente a PWA e reabra; os dados devem permanecer. Na próxima implantação/reinício normal já planejado, registre o estado antes e depois e repita a conferência. Verifique com o responsável que `/rails/storage` está montado no volume persistente `alva_storage`; a configuração versionada aponta para esse volume, mas não comprova o volume ativo no servidor. Não provoque deploy ou reinício só para este roteiro.

## Resultado e evidências

Marque cada etapa como **Passou**, **Falhou** ou **Pendente**, com data/hora e observação curta. Para falha, registre passos, aparelho e captura sem dados pessoais. Não marque como aprovado apenas com testes de sistema/CI: eles cobrem partes do comportamento, mas não substituem os dois aparelhos.

| Registro | Aparelho A | Aparelho B |
| --- | --- | --- |
| Modelo / Android / Chrome | Pendente | Pendente |
| Versão da Alva / data da rodada | Pendente | Pendente |
| HTTPS e instalação PWA | Pendente | Pendente |
| Sincronização nos dois sentidos; `/cable` `wss://` / `101` quando inspecionável | Pendente | Pendente |
| Teclado, foco, lista longa, segundo plano e bloqueio | Pendente | Pendente |
| Modo mercado, habituais, desfazer remoção e compra | Pendente | Pendente |
| Reabertura e persistência após implantação normal | Pendente | Pendente |
| Evidências (arquivos/links internos) e observações | Pendente | Pendente |

**Critério de conclusão:** todas as etapas passam nos dois Androids, a sincronização é observada nos dois sentidos sem recarga e o estado persiste após reabertura e após uma implantação/reinício normal. Até então, ALV-14 permanece em andamento.

## Base verificada e pendências

Verificado em arquivos versionados: `test/integration/https_proxy_test.rb` simula `X-Forwarded-Proto: https`; `script/smoke_test` e o job de CI também simulam o proxy. `test/integration/pwa_test.rb` verifica manifesto e service worker. Os testes de sistema cobrem foco, habituais, modo mercado e desfazer. `config/cable.yml` usa Solid Cable em produção; `config/database.yml` guarda o SQLite em `storage/production.sqlite3`; `config/deploy.yml` declara o volume `alva_storage:/rails/storage`.

Continuam pendentes: confirmar com o responsável a URL publicada e a configuração ativa do Atlas/Tailscale, TLS e proxy no servidor, WebSocket real nos aparelhos, volume persistente ativo, conectar dois Androids e registrar modelos/versões e evidências físicas. A inspeção SSH do Atlas não foi feita nesta sessão. E-mail e backup seguem pausados; este roteiro não os configura nem os declara concluídos.
