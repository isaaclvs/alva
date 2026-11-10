---
status: accepted
---

# Android com dados locais e Rails preservado

Em 10/11/2026, o usuário autorizou planejar a evolução do Alva para aplicativo
Android nativo com widget e funcionamento offline, preservando o produto atual
para sua família, sem cadastro ou login cotidiano. A direção aprovada é construir
o cliente e evoluir o Rails existente, mantendo a PWA durante a transição.

Stack escolhida, com Kotlin confirmado pelo usuário em 10/11/2026: Kotlin,
Jetpack Compose, Room,
Coroutines/Flow, WorkManager e widget inicialmente com as Views já existentes.
Android é o alvo atual e o projeto já contém código Android e assinatura; uma
stack multiplataforma acrescentaria integração para widgets sem uma necessidade
atual de iOS. Compose não obriga a alterar a identidade visual do Alva.

Rails e SQLite continuam como registro compartilhado; o banco local alimenta
telas e widget. Uma fila persistente envia comandos idempotentes e preserva
ações offline. O servidor decide a ordem compartilhada, sem confiar no relógio
dos aparelhos. Não adotaremos CRDT, event sourcing nem um novo backend para
esta etapa.

O status aceito se refere à direção arquitetural e à escolha de Kotlin.
As regras detalhadas de sincronização continuam propostas para validação na
implementação; nenhuma funcionalidade nativa offline foi entregue nesta etapa.
A especificação está em
[android-offline.md](../plans/android-offline.md).

## Consequências

- Reutilizar `android/`, applicationId, chave de assinatura e versionCode crescente
  permite planejar uma atualização do APK existente. Verificar migração de
  credenciais e widget em instalação real antes de distribuir.
- A credencial atual do widget permite apenas inclusão. Nunca ampliar seus
  poderes silenciosamente: a sincronização completa exige nova autorização.
- Normalização e categorização precisam de implementação local equivalente,
  validada pelos mesmos exemplos do Rails; não é possível chamar Ruby offline.
- As restrições históricas de PWA/sem offline em AGENTS.md e ALV-15 descrevem o
  produto anterior. Este documento registra a evolução autorizada, sem afirmar
  que a migração já aconteceu.

## Referências

- [Compose](https://developer.android.com/compose)
- [Room](https://developer.android.com/training/data-storage/room)
- [Arquitetura offline](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)
- [Glance, opção futura para widget](https://developer.android.com/develop/ui/compose/glance)
