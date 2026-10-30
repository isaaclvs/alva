import { Controller } from "@hotwired/stimulus"

const COOKIE_NAME = "screen"
const COOKIE_MAX_AGE = 60 * 60 * 24 * 365

// Switches between the two spaces of the app (Adicionar and Lista) without a
// request. The open screen is a device preference: saved in a cookie so a
// reload, or Android reopening the PWA, comes back to it, and never broadcast.
// Live updates keep filling both screens, hidden or not.
export default class extends Controller {
  static targets = ["panel", "tab"]
  static values = { current: { type: String, default: "add" } }

  connect() {
    this.render()
  }

  show(event) {
    // While shopping the list is the only screen (shopping_controller.js).
    if (document.documentElement.dataset.shoppingMode === "on") return

    const screen = event.params.screen
    if (screen === this.currentValue) {
      if (screen === "add") this.focusAddField()
      return
    }

    this.currentValue = screen
    // Replaces the old market mode cookie: the list screen does what it did.
    document.cookie = `market_mode=; Max-Age=0; Path=/; SameSite=Lax`
    document.cookie = `${COOKIE_NAME}=${screen}; Max-Age=${COOKIE_MAX_AGE}; Path=/; SameSite=Lax`
    this.render()
    window.scrollTo({ top: 0 })

    if (screen === "add") {
      this.focusAddField()
    } else {
      // Close the keyboard: the list is for tapping, not typing.
      const focused = document.activeElement
      if (focused?.matches("input, select, textarea")) focused.blur()
    }
  }

  render() {
    this.panelTargets.forEach((panel) => {
      panel.hidden = panel.dataset.screenName !== this.currentValue
    })
    this.tabTargets.forEach((tab) => {
      if (tab.dataset.screenName === this.currentValue) {
        tab.setAttribute("aria-current", "page")
      } else {
        tab.removeAttribute("aria-current")
      }
    })
  }

  // Tapping Adicionar means "I want to type", so the field is ready for it.
  focusAddField() {
    document.getElementById("list_item_name")?.focus()
  }
}
