import { Controller } from "@hotwired/stimulus"

const START_AFTER = 12 // px of horizontal travel before the row follows the finger
const REMOVE_AT = 0.4  // share of the row width that removes on release

// Swipe a row to the left to remove it (the response brings the usual toast
// with "Desfazer"). Touch only, and never in the shopping checklist, where
// rows are for ticking. The edit form has a "Remover" button for everyone
// else, so this is a shortcut, not the only way.
export default class extends Controller {
  static targets = ["row", "behind", "remove"]

  connect() {
    this.down = this.down.bind(this)
    this.move = this.move.bind(this)
    this.up = this.up.bind(this)
    this.swallowClick = this.swallowClick.bind(this)
    this.element.addEventListener("pointerdown", this.down)
    this.element.addEventListener("click", this.swallowClick, true)
  }

  disconnect() {
    this.element.removeEventListener("pointerdown", this.down)
    this.element.removeEventListener("click", this.swallowClick, true)
    this.stopListening()
  }

  get enabled() {
    return document.documentElement.dataset.shoppingMode !== "on" && this.hasRemoveTarget && this.hasRowTarget
  }

  down(event) {
    if (event.pointerType !== "touch" || !this.enabled) return
    this.startX = event.clientX
    this.startY = event.clientY
    this.dx = 0
    this.swiping = false
    this.cancelled = false
    this.element.addEventListener("pointermove", this.move)
    this.element.addEventListener("pointerup", this.up)
    this.element.addEventListener("pointercancel", this.up)
  }

  move(event) {
    if (this.cancelled) return
    const dx = event.clientX - this.startX
    const dy = event.clientY - this.startY

    if (!this.swiping) {
      // A vertical gesture is a scroll: leave it alone until the finger lifts.
      if (Math.abs(dy) > START_AFTER && Math.abs(dy) > Math.abs(dx)) { this.cancelled = true; return }
      if (dx > -START_AFTER) return
      this.swiping = true
      this.behindTarget.hidden = false
      this.rowTarget.style.background = "var(--color-surface)"
      this.element.setPointerCapture?.(event.pointerId)
    }

    this.dx = Math.min(0, dx)
    this.rowTarget.style.transition = "none"
    this.rowTarget.style.transform = `translateX(${this.dx}px)`
  }

  up() {
    this.stopListening()
    if (!this.swiping) return

    this.justSwiped = true
    const width = this.element.getBoundingClientRect().width
    if (-this.dx >= width * REMOVE_AT) {
      this.rowTarget.style.transition = "transform 150ms ease-out"
      this.rowTarget.style.transform = "translateX(-100%)"
      navigator.vibrate?.(10)
      this.removeTarget.requestSubmit()
    } else {
      this.reset()
    }
  }

  reset() {
    this.rowTarget.style.transition = "transform 150ms ease-out"
    this.rowTarget.style.transform = ""
    this.rowTarget.addEventListener("transitionend", () => {
      this.behindTarget.hidden = true
      this.rowTarget.style.background = ""
    }, { once: true })
  }

  // The finger lifting after a swipe must not also tick the item.
  swallowClick(event) {
    if (!this.justSwiped) return
    this.justSwiped = false
    event.preventDefault()
    event.stopPropagation()
  }

  stopListening() {
    this.element.removeEventListener("pointermove", this.move)
    this.element.removeEventListener("pointerup", this.up)
    this.element.removeEventListener("pointercancel", this.up)
  }
}
