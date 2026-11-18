import { Controller } from "@hotwired/stimulus"

// "Agora não" on a due "Comprar de novo" suggestion (#163): hides that
// suggestion on this device for a few days, so the hint does not become noise.
// Kept in localStorage (this device only: never a cookie the server reads,
// never a broadcast, never shared with the other phone) as a JSON map of
// normalized name → the millis when hiding ends. A later snapshot renders the
// suggestion again, and a window that is over stops hiding it.
const STORAGE_KEY = "alva.buy_again.dismissed"
const DISMISS_FOR_MS = 3 * 24 * 60 * 60 * 1000

// The same key the Rails side matches with (TextNormalizer.plain): no accent,
// no case, single spaces.
function plain(text) {
  return text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim().replace(/\s+/g, " ")
}

function openStorage() {
  try {
    return window.localStorage
  } catch (_error) {
    return null
  }
}

export default class extends Controller {
  connect() {
    this.applyDismissed()
  }

  dismiss(event) {
    // The name comes from the button's own attribute: the chip already shows it
    // and the label belongs to that exact suggestion.
    const name = event.target.dataset?.buyAgainDismiss
    if (!name) return
    const storage = openStorage()
    if (!storage) return

    const dismissed = this.read()
    dismissed[plain(name)] = Date.now() + DISMISS_FOR_MS
    try {
      storage.setItem(STORAGE_KEY, JSON.stringify(dismissed))
    } catch (_error) {
      return
    }
    this.applyDismissed()
  }

  // Hides what this device already hid and is still inside the window; also
  // forgets the entries whose window is over, so the map cannot grow forever.
  applyDismissed() {
    const dismissed = this.read()
    const now = Date.now()
    let changed = false
    for (const [ key, until ] of Object.entries(dismissed)) {
      if (!(until > now)) {
        delete dismissed[key]
        changed = true
      }
    }
    if (changed) this.write(dismissed)

    this.element.querySelectorAll("[data-buy-again-suggestion]").forEach((element) => {
      element.hidden = Boolean(dismissed[plain(element.dataset.buyAgainSuggestion || "")])
    })
    this.toggleEmpty()
  }

  // Everything hidden on this device: the section goes away too, instead of
  // leaving a title over nothing.
  toggleEmpty() {
    const group = this.element.querySelector("[role=group]")
    if (!group) return
    const visible = Array.from(this.element.querySelectorAll("[data-buy-again-suggestion]")).some((element) => !element.hidden)
    group.hidden = !visible
  }

  read() {
    const storage = openStorage()
    if (!storage) return {}
    try {
      const parsed = JSON.parse(storage.getItem(STORAGE_KEY) || "{}")
      return parsed && typeof parsed === "object" ? parsed : {}
    } catch (_error) {
      return {}
    }
  }

  write(dismissed) {
    try {
      openStorage()?.setItem(STORAGE_KEY, JSON.stringify(dismissed))
    } catch (_error) {
      // A device that refuses storage simply shows every suggestion.
    }
  }
}
