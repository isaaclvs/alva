class ListItem < ApplicationRecord
  belongs_to :household
  belongs_to :category, optional: true

  enum :status, { pending: 0, purchased: 1 }

  validates :name, presence: true
end
