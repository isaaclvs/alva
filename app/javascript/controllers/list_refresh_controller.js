import { Controller } from "@hotwired/stimulus"
import { Turbo } from "@hotwired/turbo-rails"

const REFRESH_COOLDOWN_MS = 1500
const REQUEST_TIMEOUT_MS = 12000

// ActionCable reconnects do not replay broadcasts missed while this client was
// away. Re-fetch the current list on reconnect and when the app returns to the
// foreground, while keeping any correction draft that is still on the list.
export default class extends Controller {
  static targets = ["status", "message", "retry"]
  static values = { url: String }

  connect() {
    this.refresh = this.refresh.bind(this)
    this.handleRequest = this.handleRequest.bind(this)
    this.handleUncertainMutation = this.handleUncertainMutation.bind(this)
    this.handleStreamRender = this.handleStreamRender.bind(this)
    this.handleSubmitStart = this.handleSubmitStart.bind(this)
    this.handleSubmitEnd = this.handleSubmitEnd.bind(this)
    this.handleWindowBlur = this.handleWindowBlur.bind(this)
    this.handleWindowFocus = this.handleWindowFocus.bind(this)
    this.streamRevision = 0
    this.previousCableConnected = this.cableConnected
    this.wasVisible = document.visibilityState === "visible"
    this.windowWasBlurred = false

    document.addEventListener("visibilitychange", this.refresh)
    window.addEventListener("blur", this.handleWindowBlur)
    window.addEventListener("focus", this.handleWindowFocus)
    window.addEventListener("online", this.refresh)
    document.addEventListener("list-refresh:request", this.handleRequest)
    document.addEventListener("alva:mutation-uncertain", this.handleUncertainMutation)
    document.addEventListener("turbo:before-stream-render", this.handleStreamRender)
    document.addEventListener("turbo:submit-start", this.handleSubmitStart)
    document.addEventListener("turbo:submit-end", this.handleSubmitEnd)

    this.cableObserver = new MutationObserver(() => this.cableStateChanged())
    document.querySelectorAll("turbo-cable-stream-source").forEach((source) => this.cableObserver.observe(source, {
      attributes: true,
      attributeFilter: ["connected"]
    }))

    if (!this.cableConnected) this.showStatus("Conexão em tempo real interrompida", true)
  }

  disconnect() {
    document.removeEventListener("visibilitychange", this.refresh)
    window.removeEventListener("blur", this.handleWindowBlur)
    window.removeEventListener("focus", this.handleWindowFocus)
    window.removeEventListener("online", this.refresh)
    document.removeEventListener("list-refresh:request", this.handleRequest)
    document.removeEventListener("alva:mutation-uncertain", this.handleUncertainMutation)
    document.removeEventListener("turbo:before-stream-render", this.handleStreamRender)
    document.removeEventListener("turbo:submit-start", this.handleSubmitStart)
    document.removeEventListener("turbo:submit-end", this.handleSubmitEnd)
    this.cableObserver?.disconnect()
    this.activeRequest?.abort()
  }

  get cableConnected() {
    const sources = document.querySelectorAll("turbo-cable-stream-source")
    return sources.length > 0 && Array.from(sources).every((source) => source.hasAttribute("connected"))
  }

  cableStateChanged() {
    const connected = this.cableConnected
    if (connected === this.previousCableConnected) return

    this.previousCableConnected = connected
    if (connected) {
      this.refresh({ reason: "reconnected", force: true })
    } else if (document.visibilityState === "visible") {
      this.showStatus("Conexão em tempo real interrompida", true)
    }
  }

  handleSubmitStart(event) {
    if (event.target.matches("form[data-list-refresh-correction]")) {
      this.submittedCorrectionForms ||= new WeakSet()
      this.submittedCorrectionForms.add(event.target)
    }
  }

  handleWindowBlur() {
    this.windowWasBlurred = true
  }

  handleWindowFocus() {
    if (!this.windowWasBlurred) return
    this.windowWasBlurred = false
    this.refresh({ reason: "focus", force: true })
  }

  handleSubmitEnd(event) {
    if (!event.target.matches("form[data-list-refresh-correction]")) return
    const responseStatus = event.detail?.fetchResponse?.response?.status
    if (!event.detail?.success && responseStatus !== 409) this.submittedCorrectionForms?.delete(event.target)
  }

  refresh(eventOrOptions = {}) {
    if (eventOrOptions?.type === "visibilitychange") {
      const visible = document.visibilityState === "visible"
      const returnedToForeground = visible && !this.wasVisible
      this.wasVisible = visible
      if (!returnedToForeground) return
    }
    if (document.visibilityState !== "visible") return
    const options = eventOrOptions?.detail || eventOrOptions || {}
    const eventType = eventOrOptions?.type
    const reason = options.reason || (eventType === "focus" ? "focus" : eventType === "online" ? "online" : "foreground")
    this.requestRefresh({
      reason,
      form: options.form,
      force: options.force === true || eventType === "visibilitychange" || eventType === "online"
    })
  }

  handleRequest(event) {
    const options = event.detail || {}
    this.requestRefresh({
      reason: options.reason || "requested",
      form: options.form,
      force: options.force === true
    })
  }

  handleUncertainMutation(event) {
    this.pendingUncertainForms ||= new Map()
    if (event.detail?.form) this.pendingUncertainForms.set(event.detail.form, (this.requestId || 0) + 1)
    this.requestRefresh({
      reason: "mutation-uncertain",
      form: event.detail?.form,
      force: true
    })
  }

  retry() {
    this.requestRefresh({ reason: "retry", force: true })
  }

  requestRefresh({ reason, form, force = false }) {
    if (document.visibilityState !== "visible") return

    if (this.activeRequest) {
      this.queuedRefresh = { reason, form, force: true }
      return
    }

    if (!force && Date.now() - (this.lastSuccessfulRefresh || 0) < REFRESH_COOLDOWN_MS) return

    const abortController = new AbortController()
    let timedOut = false
    const requestId = (this.requestId || 0) + 1
    this.requestId = requestId
    const startingStreamRevision = this.streamRevision
    this.activeRequest = abortController
    const timeout = setTimeout(() => {
      timedOut = true
      abortController.abort()
      const renderState = this.refreshStreamRevisions?.get(requestId)
      if (renderState) {
        renderState.stale = true
        renderState.finish()
      }
    }, REQUEST_TIMEOUT_MS)
    this.showStatus("Atualizando lista…", false)

    fetch(this.urlValue, {
      headers: { Accept: "text/vnd.turbo-stream.html" },
      credentials: "same-origin",
      signal: abortController.signal
    }).then(async (response) => {
      if (!response.ok) throw new Error(`Refresh failed (${response.status})`)
      const stream = await response.text()
      if (requestId !== this.requestId || abortController.signal.aborted) return

      if (this.streamRevision !== startingStreamRevision) {
        this.queuedRefresh = { reason, form, force: true }
        return
      }

      this.refreshStreamRevisions ||= new Map()
      const template = document.createElement("template")
      template.innerHTML = stream
      const streamElements = Array.from(template.content.querySelectorAll("turbo-stream"))
      let finishRendering
      const rendering = new Promise((resolve) => { finishRendering = resolve })
      const renderState = { startingRevision: startingStreamRevision, remaining: streamElements.length, stale: false, finish: finishRendering }
      this.refreshStreamRevisions.set(requestId, renderState)
      streamElements.forEach((element) => {
        element.dataset.listRefreshRequestId = requestId
      })
      Turbo.renderStreamMessage(template.innerHTML)
      if (renderState.remaining === 0) finishRendering()
      await rendering
      this.refreshStreamRevisions.delete(requestId)
      if (timedOut) throw new Error("Refresh timed out")
      if (abortController.signal.aborted) return
      if (renderState.stale) {
        this.queuedRefresh = { reason, form, force: true }
        return
      }
      this.lastSuccessfulRefresh = Date.now()
      if (this.cableConnected) this.hideStatus()
      else this.showStatus("Conexão em tempo real interrompida", true)
      this.dispatchRefreshResult("list-refresh:success", { reason, form, requestId, success: true })
    }).catch((error) => {
      if (requestId !== this.requestId || (error.name === "AbortError" && !timedOut)) return

      this.showStatus("Não foi possível atualizar a lista", true)
      this.dispatchRefreshResult("list-refresh:failure", { reason, form, requestId, success: false, error })
    }).finally(() => {
      clearTimeout(timeout)
      this.refreshStreamRevisions?.delete(requestId)
      if (requestId !== this.requestId) return
      this.activeRequest = null
      const queuedRefresh = this.queuedRefresh
      this.queuedRefresh = null
      if (queuedRefresh) this.requestRefresh(queuedRefresh)
    })
  }

  dispatchRefreshResult(name, detail) {
    document.dispatchEvent(new CustomEvent(name, { detail }))
    this.pendingUncertainForms ||= new Map()
    for (const [pendingForm, minimumRequestId] of this.pendingUncertainForms) {
      if (detail.requestId < minimumRequestId) continue
      this.dispatchMutationResynced(pendingForm, detail.success)
      if (detail.success) this.pendingUncertainForms.delete(pendingForm)
    }
  }

  dispatchMutationResynced(form, success) {
    document.dispatchEvent(new CustomEvent("alva:mutation-resynced", {
      detail: { form, success }
    }))
  }

  showStatus(message, canRetry) {
    if (!this.hasStatusTarget) return
    this.statusTarget.hidden = false
    this.messageTarget.textContent = message
    this.retryTarget.hidden = !canRetry
  }

  hideStatus() {
    if (this.hasStatusTarget) this.statusTarget.hidden = true
  }

  handleStreamRender(event) {
    const stream = event.target
    const refreshRequestId = Number(stream.dataset.listRefreshRequestId)
    let renderState
    if (refreshRequestId) {
      renderState = this.refreshStreamRevisions?.get(refreshRequestId)
      if (!renderState || renderState.startingRevision !== this.streamRevision) {
        event.preventDefault()
        if (renderState) {
          renderState.stale = true
          renderState.finish()
        }
        this.queuedRefresh = { reason: "stale-refresh", force: true }
        return
      }
    } else {
      this.streamRevision += 1
    }

    if (renderState) {
      const render = event.detail.render
      event.detail.render = async (streamElement) => {
        try {
          if (renderState.startingRevision !== this.streamRevision) {
            renderState.stale = true
            this.queuedRefresh = { reason: "stale-refresh", force: true }
          } else {
            await render(streamElement)
            if (renderState.startingRevision !== this.streamRevision) {
              renderState.stale = true
              this.queuedRefresh = { reason: "stale-refresh", force: true }
            }
          }
        } finally {
          renderState.remaining -= 1
          if (renderState.remaining === 0) renderState.finish()
        }
      }
    }

    if (stream.getAttribute("target") !== "list_items") return
    const render = event.detail.render
    event.detail.render = async (streamElement) => {
      if (streamElement.action !== "replace") return render(streamElement)

      // Turbo waits for a repaint before invoking its renderer. Keep the live
      // correction frames intact until the actual synchronous replace action,
      // then move those same nodes into the incoming fragment. A value snapshot
      // can go stale while typing continues during the renderer's async work.
      const originalAction = streamElement.performAction
      Object.defineProperty(streamElement, "performAction", {
        configurable: true,
        value: () => this.replaceListPreservingCorrections(streamElement, originalAction)
      })
      try {
        await render(streamElement)
      } finally {
        delete streamElement.performAction
      }
    }
  }

  replaceListPreservingCorrections(streamElement, originalAction) {
    const targets = streamElement.targetElements
    if (targets.length === 0 || streamElement.getAttribute("method") === "morph") {
      return originalAction.call(streamElement)
    }

    const fragment = streamElement.templateContent
    const activeElement = document.activeElement
    const selectionStart = this.selectionStart(activeElement)
    const selectionEnd = this.selectionEnd(activeElement)
    const correctionForms = Array.from(document.querySelectorAll("#list_items turbo-frame form[data-list-refresh-correction]"))
      .filter((form) => !this.submittedCorrectionForms?.has(form))

    correctionForms.forEach((form) => {
      const editingFrame = form.closest("turbo-frame")
      if (!editingFrame) return

      const incomingFrame = fragment.querySelector(`#${CSS.escape(editingFrame.id)}`)
      if (!incomingFrame) {
        this.showStatus("Este item saiu da lista enquanto você o corrigia", false)
        return
      }

      editingFrame.removeAttribute("src")
      incomingFrame.replaceWith(editingFrame)
    })

    targets.forEach((target) => target.replaceWith(fragment))
    if (activeElement?.isConnected && document.activeElement !== activeElement) {
      activeElement.focus({ preventScroll: true })
      if (selectionStart !== null && typeof activeElement.setSelectionRange === "function") {
        activeElement.setSelectionRange(selectionStart, selectionEnd)
      }
    }
  }

  selectionStart(control) {
    try {
      return control.selectionStart ?? null
    } catch (_error) {
      return null
    }
  }

  selectionEnd(control) {
    try {
      return control.selectionEnd ?? null
    } catch (_error) {
      return null
    }
  }
}
