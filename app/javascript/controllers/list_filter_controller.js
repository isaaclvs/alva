import { Controller } from "@hotwired/stimulus"

// Controls stay outside broadcast fragments. Reapply local filters whenever
// Turbo replaces the list, without replacing the search field or its focus.
export default class extends Controller {
  static targets = ["query", "pending", "clear", "empty"]

  connect() {
    this.observer = new MutationObserver(() => this.apply())
    this.observer.observe(this.element, { childList: true, subtree: true })
    this.apply()
  }

  disconnect() {
    this.observer.disconnect()
  }

  apply() {
    const query = this.queryTarget.value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim().replace(/\s+/g, " ")
    let visible = 0
    this.element.querySelectorAll(".shopping-category").forEach((category) => {
      let matches = 0
      category.querySelectorAll("li[data-search-name]").forEach((row) => {
        row.hidden = !row.dataset.searchName.includes(query) || (this.pendingTarget.checked && row.dataset.purchased === "true")
        if (!row.hidden) matches += 1
      })
      category.hidden = matches === 0
      visible += matches
    })
    this.clearTarget.hidden = !this.queryTarget.value
    this.emptyTarget.hidden = visible > 0 || (!query && !this.pendingTarget.checked)
  }

  clear() {
    this.queryTarget.value = ""
    this.apply()
    this.queryTarget.focus()
  }

  reset() {
    this.queryTarget.value = ""
    this.pendingTarget.checked = false
    this.apply()
  }
}
