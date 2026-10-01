class Category < ApplicationRecord
  has_many :catalog_items
  has_many :list_items

  validates :name, presence: true, uniqueness: true
  validates :position, presence: true
end
