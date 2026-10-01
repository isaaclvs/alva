class Category < ApplicationRecord
  has_many :catalog_items

  validates :name, presence: true, uniqueness: true
  validates :position, presence: true
end
