class Household < ApplicationRecord
  has_many :list_items

  validates :name, presence: true

  # [[category, items], ...] ordered by category position; uncategorized last.
  # Only categories with at least one item show up.
  def list_items_by_category
    list_items.includes(:category).order(:created_at, :id)
      .group_by(&:category)
      .sort_by { |category, _| category&.position || Float::INFINITY }
  end
end
