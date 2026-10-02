class Household < ApplicationRecord
  has_many :list_items

  validates :name, presence: true

  # Pending item whose name matches after normalization ("Leite" == " leite ").
  def pending_item_named(name)
    key = TextNormalizer.call(name)
    list_items.pending.find { |item| TextNormalizer.call(item.name) == key }
  end

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up.
  def list_items_by_category
    list_items.includes(:category).order(:created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
