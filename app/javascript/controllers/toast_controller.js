import { Controller } from "@hotwired/stimulus"

// Removes the toast after a few seconds. A new toast replaces the previous one
// (the #toast container is updated), which disconnects this controller and
// cancels its timer.
export default class extends Controller {
  static values = { delay: { type: Number, default: 5000 } }

  connect() {
    this.timeout = setTimeout(() => this.element.remove(), this.delayValue)
  }

  disconnect() {
    clearTimeout(this.timeout)
  }
}
