class Household < ApplicationRecord
  has_many :list_items

  validates :name, presence: true

  # Pending item whose name matches after normalization ("Leite" == " leite ").
  def pending_item_named(name)
    key = TextNormalizer.call(name)
    list_items.pending.find { |item| TextNormalizer.call(item.name) == key }
  end

  # Deletes in one query and broadcasts once, instead of once per item.
  def clear_purchased!
    list_items.purchased.delete_all
    broadcast_list
  end

  # Re-render the whole grouped list for every client on this household's stream:
  # a change can add or empty a category section, not just touch one row.
  def broadcast_list
    broadcast_replace_to self, target: "list_items", partial: "list_items/list",
      locals: { groups: list_items_by_category }
  end

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up.
  def list_items_by_category
    list_items.includes(:category).order(:created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
