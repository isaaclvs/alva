module ListItemsHelper
  # Categories are fixed (db/seeds/catalog.yml), so their look lives here
  # instead of in the database. Unknown or missing categories get the default.
  CATEGORY_ICONS = {
    "Hortifruti" => [ "M20 4C10 2 3 7 5 14c2 6 13 6 15-10Z M4 21 15 10", "produce" ],
    "Laticínios" => [ "M9 3h6v4l3 4v10H6V11l3-4V3Z M9 7h6 M6 13h12", "dairy" ],
    "Mercearia" => [ "M5 8h14l1 13H4L5 8Z M9 8V6a3 3 0 0 1 6 0v2", "pantry" ],
    "Açougue" => [ "M15 3c5 0 7 5 4 9l-6 7c-3 3-9 1-10-3S6 8 9 7s2-4 6-4Z M14 7c3-1 5 2 3 4s-5-1-3-4Z", "meat" ],
    "Padaria" => [ "M4 20c-3-3 1-8 5-12s9-7 12-4-1 8-5 12-9 7-12 4Z M8 10l3 3 M12 6l3 3 M5 14l3 3", "bakery" ],
    "Bebidas" => [ "M6 8h12l-2 13H8L6 8Z M5 8h14 M12 8l3-6h4", "drinks" ],
    "Limpeza" => [ "M8 10h8l3 4v7H5v-7l3-4Z M10 10V6h4v4 M10 6V3h7l2 2 M8 16h8", "cleaning" ],
    "Higiene" => [ "M8 8h8l2 4v9H6v-9l2-4Z M10 8V4h4v4 M10 4V2h7 M10 15h4 M12 13v4", "hygiene" ]
  }.freeze
  DEFAULT_CATEGORY_ICON = [ "M3 7l9-5 9 5v10l-9 5-9-5V7Z M3 7l9 5 9-5 M12 12v10 M7 4l10 6", "other" ].freeze

  def category_icon(category)
    path, tone = CATEGORY_ICONS.fetch(category&.name, DEFAULT_CATEGORY_ICON)
    tag.span class: "category-icon", data: { tone: tone }, aria: { hidden: true } do
      tag.svg(viewBox: "0 0 24 24", fill: "none", stroke: "currentColor",
        "stroke-width": "1.6", "stroke-linecap": "round", "stroke-linejoin": "round",
        focusable: "false") { tag.path(d: path) }
    end
  end

  # "6 pendentes · 2 comprados"; the purchased part only once something was
  # bought. An empty list says so.
  def list_summary_text(pending_count, purchased_count)
    return "Nada na lista" if pending_count.zero? && purchased_count.zero?

    parts = [ pluralize(pending_count, "pendente") ]
    parts << pluralize(purchased_count, "comprado") if purchased_count.positive?
    parts.join(" · ")
  end

  # Feedback for adding one name, for the add screen (the list is not on it).
  def add_item_notice(result)
    item = result.item
    case result.outcome
    when :created then "#{item.name} adicionado em #{Category.label_for(item.category)}"
    when :revived then "#{item.name} voltou para a lista"
    when :duplicate then "#{item.name} já está na lista"
    end
  end

  # Options for the add field's <datalist>, rendered together with the form so
  # every re-render of the form (after an add) also refreshes the suggestions.
  def name_suggestions_datalist(id)
    tag.datalist(id: id) do
      safe_join(NameSuggestions.call(current_household).map { |name| tag.option(value: name) })
    end
  end

  PT_LIST_CONNECTORS = { words_connector: ", ", two_words_connector: " e ", last_word_connector: " e " }.freeze

  # Feedback for adding several names at once, from the Household#add_items
  # results: "3 itens adicionados", "2 itens adicionados · Leite já estava na
  # lista" or "Nada adicionado · Leite e Pão já estavam na lista". Revived items
  # count as added.
  def add_items_notice(results)
    added = results.count { |result| result.outcome.in?(%i[created revived]) }
    already = results.select { |result| result.outcome == :duplicate }.map { |result| result.item.name }

    parts = []
    parts << (added.zero? ? "Nada adicionado" : "#{added} #{added == 1 ? 'item adicionado' : 'itens adicionados'}")
    if already.any?
      verb = already.one? ? "já estava" : "já estavam"
      parts << "#{already.to_sentence(**PT_LIST_CONNECTORS)} #{verb} na lista"
    end
    parts.join(" · ")
  end
end
