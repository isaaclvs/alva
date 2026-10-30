import { Controller } from "@hotwired/stimulus"

const CHANGED_EVENT = "alva:theme-changed"

// Flips between the dark and light theme without a request. The server
// renders <html data-theme> from the "theme" cookie (one per device, never
// broadcast), so this only has to keep the attribute, the cookie, the browser
// chrome color and every toggle's label in sync.
export default class extends Controller {
  static values = { darkColor: String, lightColor: String }

  connect() {
    this.sync = this.sync.bind(this)
    document.addEventListener(CHANGED_EVENT, this.sync)
    this.sync()
  }

  disconnect() {
    document.removeEventListener(CHANGED_EVENT, this.sync)
  }

  toggle() {
    const next = this.theme === "light" ? "dark" : "light"
    document.documentElement.dataset.theme = next
    document.cookie = `theme=${next}; path=/; max-age=31536000; SameSite=Lax`
    document.querySelector("meta[name=theme-color]")?.setAttribute("content", this.colorFor(next))
    document.dispatchEvent(new CustomEvent(CHANGED_EVENT))
  }

  get theme() {
    return document.documentElement.dataset.theme === "light" ? "light" : "dark"
  }

  colorFor(theme) {
    return theme === "light" ? this.lightColorValue : this.darkColorValue
  }

  // The label names the theme the button switches to.
  sync() {
    const label = this.theme === "dark" ? "Tema claro" : "Tema escuro"
    this.element.title = label
    this.element.setAttribute("aria-label", label)
  }
}
