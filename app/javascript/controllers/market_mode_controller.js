import { Controller } from "@hotwired/stimulus"

const COOKIE_NAME = "market_mode"
const COOKIE_MAX_AGE = 60 * 60 * 24 * 365

// Shopping mode is a device preference. It only hides the entry controls in
// this browser; the shared list continues to update through Turbo Streams.
export default class extends Controller {
  static targets = ["entryControls", "toggle"]
  static values = { active: Boolean }

  connect() {
    this.renderState()
  }

  toggle() {
    this.activeValue = !this.activeValue
    if (this.activeValue) {
      const focusedControl = document.activeElement
      if (this.entryControlsTarget.contains(focusedControl) && focusedControl.matches("input, select, textarea, [contenteditable='true']")) {
        focusedControl.blur()
      }
    }
    document.cookie = this.activeValue
      ? `${COOKIE_NAME}=true; Max-Age=${COOKIE_MAX_AGE}; Path=/; SameSite=Lax`
      : `${COOKIE_NAME}=; Max-Age=0; Path=/; SameSite=Lax`
    this.renderState()
  }

  renderState() {
    this.entryControlsTarget.hidden = this.activeValue
    this.toggleTarget.setAttribute("aria-pressed", String(this.activeValue))
    this.toggleTarget.textContent = this.activeValue ? "Voltar à lista" : "Ir ao mercado"
  }
}
