// The browser twin of TextNormalizer.plain: no accents, case or extra spaces,
// and a look-alike digit inside a word read as its letter ("far0fa" → "farofa").
// Rows carry the server's version in data-search-name, so both sides agree.
const LOOKALIKES = { 0: "o", 1: "i", 3: "e", 4: "a", 5: "s", 7: "t" }

// A digit is a typo for a letter only when it has a letter right before it and
// another letter right after it. "leite 0%", "coca 2l", "500g", "7up",
// "vitamina b12", "vitamina d3", "omega3" and "v8" keep their digits: a digit at
// either edge of the word is never folded.
function foldDigits(word) {
  const chars = [...word]
  return chars.map((char, index) => {
    const lookalike = LOOKALIKES[char]
    if (!lookalike || index === 0) return char
    if (!/[a-z]/.test(chars[index - 1])) return char
    if (!/[a-z]/.test(chars[index + 1] ?? "")) return char
    return lookalike
  }).join("")
}

export function plain(text) {
  return text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim().replace(/\s+/g, " ")
    .split(" ").map(foldDigits).join(" ")
}
