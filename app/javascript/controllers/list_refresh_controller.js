import { Controller } from "@hotwired/stimulus"
import { Turbo } from "@hotwired/turbo-rails"

// Refetches the list when the page becomes visible again. While the phone was
// locked or the app was in the background the cable connection may have
// dropped, and broadcasts missed in that window are not replayed on reconnect.
export default class extends Controller {
  static values = { url: String }

  connect() {
    this.refresh = this.refresh.bind(this)
    document.addEventListener("visibilitychange", this.refresh)
  }

  disconnect() {
    document.removeEventListener("visibilitychange", this.refresh)
  }

  async refresh() {
    if (document.visibilityState !== "visible") return

    const response = await fetch(this.urlValue, { headers: { Accept: "text/vnd.turbo-stream.html" } })
    if (response.ok) Turbo.renderStreamMessage(await response.text())
  }
}
