class ListItem < ApplicationRecord
  belongs_to :household
  belongs_to :category, optional: true

  enum :status, { pending: 0, purchased: 1 }

  validates :name, presence: true

  before_create :assign_category, unless: :category

  def toggle_purchased!
    if purchased?
      update!(status: :pending, purchased_at: nil)
    else
      update!(status: :purchased, purchased_at: Time.current)
    end
  end

  private

  def assign_category
    self.category = ItemCategorizer.call(name)
  end
end
