import { Controller } from "@hotwired/stimulus"

// Submits its form as soon as a field changes (the category picker under
// "Acabou de adicionar"), so fixing a category is a single tap.
export default class extends Controller {
  submit() {
    this.element.requestSubmit()
  }
}
