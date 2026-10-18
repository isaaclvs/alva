class ListItem < ApplicationRecord
  belongs_to :household
  belongs_to :category, optional: true
  belongs_to :shopping_trip, optional: true

  enum :status, { pending: 0, purchased: 1 }

  # An item with a shopping_trip is archived history: it left the live list when
  # that trip was finished. Every query behind the live list (rendering, counts,
  # duplicate detection, finding an item to toggle or remove) must go through
  # on_list; archived is the history, read by "buy again" and undo.
  scope :on_list, -> { where(shopping_trip_id: nil) }
  scope :archived, -> { where.not(shopping_trip_id: nil) }

  validates :name, presence: true
  validate :not_already_pending, on: :create

  before_create :assign_category, unless: :category

  after_commit -> { household.list_changed }

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
