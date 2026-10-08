class ListItem < ApplicationRecord
  belongs_to :household
  belongs_to :category, optional: true

  enum :status, { pending: 0, purchased: 1 }

  validates :name, presence: true
  validate :not_already_pending, on: :create

  before_create :assign_category, unless: :category

  after_commit -> { household.broadcast_list }

  def toggle_purchased!
    if purchased?
      update!(status: :pending, purchased_at: nil)
    else
      update!(status: :purchased, purchased_at: Time.current)
    end
  end

  private

  # No quantities in this app, so a second pending "leite" adds nothing.
  # Purchased items don't count: buying it again starts a new pending entry.
  def not_already_pending
    return if name.blank? || household.nil?

    errors.add(:name, :duplicate_pending, message: "já está na lista") if household.pending_item_named(name)
  end

  def assign_category
    self.category = ItemCategorizer.call(name)
  end
end
