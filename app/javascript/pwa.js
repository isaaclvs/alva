// Registra o service worker pra permitir "adicionar à tela inicial".
// Sem cache/offline (decisão de produto) — o worker só existe pra
// satisfazer o critério de instalabilidade do browser.
if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/service-worker.js")
}
