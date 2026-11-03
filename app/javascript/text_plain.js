// The browser twin of TextNormalizer.plain: no accents, case or extra spaces.
// Rows carry the server's version in data-search-name, so both sides agree.
export function plain(text) {
  return text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim().replace(/\s+/g, " ")
}
