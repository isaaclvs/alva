import { Controller } from "@hotwired/stimulus"
import { plain } from "text_plain"

const SEPARATORS = /[,;\n]/ // same as ItemNameSplitter

// While typing, says which names are already pending on the list (the list
// screen is on the same page, just hidden). Only a hint: the server still
// decides, and also catches plurals ("bananas" vs "banana") that this misses.
export default class extends Controller {
  static targets = ["input", "hint"]

  check() {
    const pending = new Map()
    document.querySelectorAll("#list_items li[data-search-name][data-purchased=false]").forEach((row) => {
      pending.set(row.dataset.searchName, row.querySelector(".shopping-toggle span:last-child")?.textContent.trim())
    })

    const already = [...new Set(this.inputTarget.value.split(SEPARATORS).map(plain).filter(Boolean))]
      .filter((name) => pending.has(name))
      .map((name) => pending.get(name) || name)

    this.hintTarget.hidden = already.length === 0
    this.hintTarget.textContent = already.length === 1
      ? `${already[0]} já está na lista`
      : `${already.slice(0, -1).join(", ")} e ${already.at(-1)} já estão na lista`
  }
}
