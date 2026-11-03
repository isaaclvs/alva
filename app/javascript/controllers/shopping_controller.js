import { Controller } from "@hotwired/stimulus"

const COOKIE_NAME = "shopping_mode"
const COOKIE_MAX_AGE = 60 * 60 * 24
const SCREEN_COOKIE = "screen"
const EDITING_FORM = "#list_items form[data-list-refresh-correction]"

// "Iniciar compras": the list screen as a checklist. The mode is a device
// preference (cookie, rendered by the server on <html data-shopping-mode> so a
// reload does not flash controls or keyboard) and is never broadcast. The
// hiding itself is CSS keyed on that attribute, so list fragments that arrive
// by broadcast need no help from this controller.
export default class extends Controller {
  static targets = ["start", "hint", "title"]

  connect() {
    // The start button lives in the summary, which broadcasts replace, so the
    // "an edit is open" state is re-synced on every change of the page.
    this.observer = new MutationObserver(() => this.syncEditing())
    this.observer.observe(this.element, { childList: true, subtree: true })
    this.syncEditing()

    // In the store the phone sits in one hand between ticks: keep the screen
    // on, and confirm each tick with a short buzz.
    this.onVisible = () => { if (document.visibilityState === "visible") this.syncWakeLock() }
    this.onTick = (event) => { if (this.active && event.target.closest(".shopping-toggle")) navigator.vibrate?.(10) }
    document.addEventListener("visibilitychange", this.onVisible)
    this.element.addEventListener("click", this.onTick)
    this.syncWakeLock()
  }

  disconnect() {
    this.observer?.disconnect()
    document.removeEventListener("visibilitychange", this.onVisible)
    this.element.removeEventListener("click", this.onTick)
    this.releaseWakeLock()
  }

  // The browser drops the lock whenever the page is hidden, so it is asked
  // for again on every return. Unsupported or refused: the list still works.
  async syncWakeLock() {
    if (!this.active) return this.releaseWakeLock()
    if (this.wakeLock && !this.wakeLock.released) return
    try {
      this.wakeLock = await navigator.wakeLock?.request("screen")
    } catch {
      this.wakeLock = null
    }
  }

  releaseWakeLock() {
    this.wakeLock?.release().catch(() => {})
    this.wakeLock = null
  }

  get active() {
    return document.documentElement.dataset.shoppingMode === "on"
  }

  get editingForm() {
    return document.querySelector(EDITING_FORM)
  }

  start() {
    // An open correction form keeps its draft and is never exposed inside the
    // checklist: finish (or cancel) it first.
    const editing = this.editingForm
    if (editing) {
      editing.querySelector("input:not([type=hidden]), select")?.focus()
      return
    }

    document.cookie = `${COOKIE_NAME}=on; Max-Age=${COOKIE_MAX_AGE}; Path=/; SameSite=Lax`
    document.cookie = `${SCREEN_COOKIE}=list; Max-Age=${60 * 60 * 24 * 365}; Path=/; SameSite=Lax`
    document.documentElement.dataset.shoppingMode = "on"

    // The list is for tapping, not typing.
    const focused = document.activeElement
    if (focused?.matches("input, select, textarea")) focused.blur()
    window.scrollTo({ top: 0 })
    this.titleTarget.focus()
    this.syncWakeLock()
  }

  exit() {
    document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/; SameSite=Lax`
    delete document.documentElement.dataset.shoppingMode
    this.releaseWakeLock()
    this.dispatch("exited")
    window.scrollTo({ top: 0 })
    const next = this.hasStartTarget ? this.startTarget : document.getElementById("list_screen_title")
    next.focus()
  }

  syncEditing() {
    const editing = !!this.editingForm
    this.startTargets.forEach((button) => button.setAttribute("aria-disabled", String(editing)))
    this.hintTargets.forEach((hint) => { hint.hidden = !editing })
  }
}
