class Household < ApplicationRecord
  has_many :list_items

  validates :name, presence: true

  # Pending item whose name matches after normalization ("Leite" == " leite ").
  def pending_item_named(name)
    key = TextNormalizer.call(name)
    list_items.pending.find { |item| TextNormalizer.call(item.name) == key }
  end

  # What add_item did: `item` is the affected ListItem (the new, revived or
  # already pending one; unsaved with errors when invalid) and `outcome` is
  # one of :created, :duplicate or :invalid.
  AddResult = Struct.new(:item, :outcome)

  # The single entry point for putting a name on the list. Returns an AddResult:
  # - :created   a new pending item was saved;
  # - :duplicate the name is already pending; `item` is that existing item;
  # - :invalid   nothing saved; `item.errors` says why (e.g. blank name).
  def add_item(name)
    existing = pending_item_named(name)
    return AddResult.new(existing, :duplicate) if existing

    item = list_items.new(name: name)
    AddResult.new(item, item.save ? :created : :invalid)
  end

  # Deletes in one query and broadcasts once, instead of once per item.
  def clear_purchased!
    list_items.purchased.delete_all
    broadcast_list
  end

  # Re-render the whole grouped list and its summary for every client on this
  # household's stream: a change can add or empty a category section, not just
  # touch one row. Both go in a single broadcast.
  def broadcast_list
    Turbo::StreamsChannel.broadcast_render_to self, partial: "list_items/refresh",
      locals: { groups: list_items_by_category }
  end

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up. Within a category, pending
  # items come first so what is still missing stays at the top.
  def list_items_by_category
    list_items.includes(:category).order(:status, :created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
