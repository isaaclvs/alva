import { Controller } from "@hotwired/stimulus"

export default class extends Controller {
  connect() {
    this.sync = this.sync.bind(this)
    document.addEventListener("alva:color-changed", this.sync)
    this.sync()
  }

  disconnect() {
    document.removeEventListener("alva:color-changed", this.sync)
  }

  select(event) {
    const button = event.target.closest("[data-color-picker-value-param]")
    if (!button) return
    const color = button.dataset.colorPickerValueParam === "pink" ? "pink" : "teal"
    document.documentElement.dataset.color = color
    document.cookie = `color=${color}; path=/; max-age=31536000; SameSite=Lax`
    const theme = document.documentElement.dataset.theme === "light" ? "light" : "dark"
    const colors = color === "pink" ? { dark: "#151014", light: "#fcf8fa" } : { dark: "#09090B", light: "#F4F4F5" }
    document.querySelector("meta[name=theme-color]")?.setAttribute("content", colors[theme])
    document.dispatchEvent(new CustomEvent("alva:color-changed"))
  }

  sync() {
    this.element.querySelectorAll("[data-color-picker-value-param]").forEach((option) => {
      option.setAttribute("aria-pressed", String(option.dataset.colorPickerValueParam === document.documentElement.dataset.color))
    })
  }
}
