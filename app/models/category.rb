class Category < ApplicationRecord
  # Heading for items without a category (only items added before
  # categorization existed). Distinct from the real "Outros" category.
  NONE_LABEL = "Sem categoria".freeze

  has_many :catalog_items
  has_many :list_items

  validates :name, presence: true, uniqueness: true
  validates :position, presence: true

  # Heading for a group of items, nil meaning uncategorized.
  def self.label_for(category)
    category&.name || NONE_LABEL
  end
end
