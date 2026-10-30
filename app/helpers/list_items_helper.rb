module ListItemsHelper
  # Categories are fixed (db/seeds/catalog.yml), so their look lives here
  # instead of in the database. Unknown or missing categories get the default.
  CATEGORY_ICONS = {
    "Hortifruti" => [ "🥬", "bg-emerald-500/15" ],
    "Laticínios" => [ "🧀", "bg-sky-500/15" ],
    "Mercearia" => [ "🥫", "bg-amber-500/15" ],
    "Açougue" => [ "🥩", "bg-rose-500/15" ],
    "Padaria" => [ "🥖", "bg-orange-500/15" ],
    "Bebidas" => [ "🥤", "bg-cyan-500/15" ],
    "Limpeza" => [ "🧽", "bg-indigo-500/15" ],
    "Higiene" => [ "🧴", "bg-fuchsia-500/15" ]
  }.freeze
  DEFAULT_CATEGORY_ICON = [ "📦", "bg-zinc-500/15" ].freeze

  def category_icon(category)
    emoji, background = CATEGORY_ICONS.fetch(category&.name, DEFAULT_CATEGORY_ICON)
    tag.span emoji, class: "grid size-7 place-items-center rounded-lg text-sm #{background}", aria: { hidden: true }
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
