import { Controller } from "@hotwired/stimulus"

// The separators of ItemNameSplitter (comma, semicolon, line feed): a segment is
// one name, and the characters between them are left exactly as typed.
const SEPARATORS = [",", ";", "\n"]
const MAX_LENGTH = 100 // ListItem::MAX_NAME_LENGTH

// The add field holds several names, so a plain `maxlength` on the input would
// cap the whole text and break "leite, pão" once the names together pass 100
// characters. Each name is cut at the limit the server applies
// (ItemNameSplitter.clean: stripped, then cut) and the hint below the field says
// so while it is being cut, so the person knows why the text stopped growing.
export default class extends Controller {
  static targets = ["input", "hint"]

  limit() {
    const text = this.inputTarget.value
    const limited = limitText(text)
    this.hintTarget.hidden = limited === text
    if (limited === text) return

    const caretAtEnd = this.inputTarget.selectionStart === text.length
    this.inputTarget.value = limited
    if (caretAtEnd) this.inputTarget.setSelectionRange(limited.length, limited.length)
  }
}

// Every run of characters between separators, cut at MAX_LENGTH. The twin of
// ItemNameSplitter.limitDraft on the Android side; the server rule
// (ItemNameSplitter.clean) stays the authority.
export function limitText(text) {
  let out = ""
  let length = 0
  for (const char of text) {
    if (SEPARATORS.includes(char)) {
      length = 0
      out += char
    } else if (length < MAX_LENGTH) {
      length += 1
      out += char
    }
  }
  return out
}
