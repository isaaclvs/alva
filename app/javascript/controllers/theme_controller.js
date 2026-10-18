import { Controller } from "@hotwired/stimulus"

// Flips between the dark and light theme without a request. The server
// renders <html data-theme> from the "theme" cookie, so this only has to keep
// the attribute, the cookie and the browser chrome color in sync.
export default class extends Controller {
  static values = { darkColor: String, lightColor: String }

  connect() {
    this.sync()
  }

  toggle() {
    const next = this.theme === "light" ? "dark" : "light"
    document.documentElement.dataset.theme = next
    document.cookie = `theme=${next}; path=/; max-age=31536000; SameSite=Lax`
    document.querySelector("meta[name=theme-color]")?.setAttribute("content", this.colorFor(next))
    this.sync()
  }

  get theme() {
    return document.documentElement.dataset.theme === "light" ? "light" : "dark"
  }

  colorFor(theme) {
    return theme === "light" ? this.lightColorValue : this.darkColorValue
  }

  // The label names the theme the button switches to. It is set here rather
  // than in the view because the summary is also rendered by broadcasts,
  // which cannot read the cookie.
  sync() {
    const label = this.theme === "dark" ? "Tema claro" : "Tema escuro"
    this.element.title = label
    this.element.setAttribute("aria-label", label)
  }
}
