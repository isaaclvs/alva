# A finished shopping trip ("Compra feita"): when it happened and, through
# list_items, what was bought. The items stay in the database as history, but
# off the live list (see ListItem.on_list / ListItem.archived).
class ShoppingTrip < ApplicationRecord
  belongs_to :household
  has_many :list_items

  validates :finished_at, presence: true
end
