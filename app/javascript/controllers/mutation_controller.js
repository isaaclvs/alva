import { Controller } from "@hotwired/stimulus"

const REQUEST_ERROR = "Falha ao salvar. Conferindo a lista antes de liberar outra tentativa."
const RESYNCED_ERROR = "A lista foi atualizada. Confira o estado antes de tentar novamente."
const RESYNC_FAILED_ERROR = "Não foi possível confirmar o resultado. Atualize a lista antes de tentar novamente."

// A failed response can mean that the server committed the action and only the
// response was lost, so every failed mutation asks list-refresh to reconcile
// before another submission.
export default class extends Controller {
  connect() {
    this.inFlight = new WeakSet()
    this.activeActions = new Set()
    this.pendingActions = new Set()
    this.handleSubmit = this.handleSubmit.bind(this)
    this.handleSubmitStart = this.handleSubmitStart.bind(this)
    this.handleSubmitEnd = this.handleSubmitEnd.bind(this)
    this.handleResynced = this.handleResynced.bind(this)

    document.addEventListener("submit", this.handleSubmit, true)
    document.addEventListener("turbo:submit-start", this.handleSubmitStart)
    document.addEventListener("turbo:submit-end", this.handleSubmitEnd)
    document.addEventListener("alva:mutation-resynced", this.handleResynced)
  }

  disconnect() {
    document.removeEventListener("submit", this.handleSubmit, true)
    document.removeEventListener("turbo:submit-start", this.handleSubmitStart)
    document.removeEventListener("turbo:submit-end", this.handleSubmitEnd)
    document.removeEventListener("alva:mutation-resynced", this.handleResynced)
  }

  handleSubmit(event) {
    const form = event.target
    if (!(form instanceof HTMLFormElement)) return

    const key = this.actionKey(form)
    if (this.inFlight.has(form) || this.activeActions.has(key) || this.pendingActions.has(key)) {
      event.preventDefault()
      event.stopImmediatePropagation()
      this.showError(form, "Aguarde a resposta antes de tentar novamente.")
      return
    }

    this.inFlight.add(form)
    this.activeActions.add(key)
    form.dataset.mutationInFlight = "true"
    form.dataset.mutationActionKey = key
    this.clearError(form)
  }

  handleSubmitStart(event) {
    const form = event.detail.formSubmission?.formElement
    if (!(form instanceof HTMLFormElement)) return

    form.dataset.mutationSubmitting = "true"
    form.querySelectorAll("button, input[type=submit]").forEach((control) => {
      if (control.disabled) return
      control.dataset.mutationWasEnabled = "true"
      control.disabled = true
    })
  }

  handleSubmitEnd(event) {
    const form = event.detail.formSubmission?.formElement
    if (!(form instanceof HTMLFormElement)) return

    const key = form.dataset.mutationActionKey || this.actionKey(form)
    this.inFlight.delete(form)
    this.activeActions.delete(key)
    delete form.dataset.mutationInFlight
    delete form.dataset.mutationSubmitting
    form.querySelectorAll('[data-mutation-was-enabled="true"]').forEach((control) => {
      control.disabled = false
      delete control.dataset.mutationWasEnabled
    })

    if (event.detail.success) return

    // A 422 is an application validation response whose form already explains
    // the problem. Other failures leave the submitted values available.
    const status = event.detail.fetchResponse?.statusCode
    if (status !== 422 && status !== 409) this.showError(form, REQUEST_ERROR)

    this.pendingActions.add(key)
    form.dataset.mutationPendingAction = key
    document.dispatchEvent(new CustomEvent("alva:mutation-uncertain", {
      detail: {
        form,
        url: form.action,
        method: form.method.toUpperCase(),
        submitter: event.detail.formSubmission?.submitter
      }
    }))
  }

  handleResynced(event) {
    const { form, success } = event.detail || {}
    if (!(form instanceof HTMLFormElement)) return

    const key = form.dataset.mutationPendingAction || this.actionKey(form)
    if (!success) {
      this.showError(form, RESYNC_FAILED_ERROR)
      return
    }

    this.pendingActions.delete(key)
    delete form.dataset.mutationPendingAction
    this.showError(form, RESYNCED_ERROR)
  }

  actionKey(form) {
    return `${form.method.toUpperCase()} ${new URL(form.action, window.location.href).pathname}`
  }

  showError(form, message) {
    const status = document.getElementById("mutation_status")
    if (!status) return
    status.textContent = message
    status.hidden = false
  }

  clearError(form) {
    const status = document.getElementById("mutation_status")
    if (status) status.hidden = true
  }
}
