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

  # "6 faltando · 2 no carrinho", dropping the parts that are zero.
  def list_summary_text(pending_count, purchased_count)
    parts = []
    parts << "#{pending_count} faltando" if pending_count.positive?
    parts << "#{purchased_count} no carrinho" if purchased_count.positive?
    parts.any? ? parts.join(" · ") : "Nada faltando"
  end
end
