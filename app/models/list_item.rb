class ListItem < ApplicationRecord
  belongs_to :household
  belongs_to :category, optional: true

  enum :status, { pending: 0, purchased: 1 }

  validates :name, presence: true

  before_create :assign_category, unless: :category

  # Re-render the whole grouped list for every client on the household stream:
  # a change can add or empty a category section, not just touch one row.
  after_commit :broadcast_list

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

  def broadcast_list
    broadcast_replace_to household, target: "list_items", partial: "list_items/list",
      locals: { groups: household.list_items_by_category }
  end
end
