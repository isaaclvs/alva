import { Controller } from "@hotwired/stimulus"

// Device-local feedback: retain only the three newest additions.
export default class extends Controller {
  static targets = ["item"]

  itemTargetConnected() {
    this.itemTargets.slice(3).forEach(item => item.remove())
  }
}
