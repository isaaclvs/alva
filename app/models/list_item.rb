class ListItem < ApplicationRecord
  class StaleAction < StandardError; end

  belongs_to :household
  belongs_to :category, optional: true
  belongs_to :shopping_trip, optional: true

  enum :status, { pending: 0, purchased: 1 }

  # How long a row that just became pending keeps flashing. Long enough that
  # the broadcast arriving right after this device's own response doesn't
  # render the row again without the flash.
  JUST_LISTED_FOR = 3.seconds

  # An item with a shopping_trip is archived history: it left the live list when
  # that trip was finished. Every query behind the live list (rendering, counts,
  # duplicate detection, finding an item to toggle or remove) must go through
  # on_list; archived is the history, read by "buy again" and undo.
  scope :on_list, -> { where(shopping_trip_id: nil) }
  scope :archived, -> { where.not(shopping_trip_id: nil) }

  validates :name, presence: true
  validate :not_already_pending, on: :create
  validate :not_renamed_to_a_pending_item, on: :update, if: :will_save_change_to_name?

  before_create :assign_category, unless: :category
  before_validation :normalize_name

  after_commit -> { household.list_changed }

  # Just became pending (added, brought back or unticked): every device that
  # renders the row in the next few seconds shows it highlighted.
  def just_listed?
    pending? && updated_at.present? && updated_at > JUST_LISTED_FOR.ago
  end

  def toggle_purchased!
    expected_version = lock_version
    household.with_list_lock do
      reload
      raise StaleAction unless lock_version == expected_version
      raise StaleAction unless shopping_trip_id.nil?

      if purchased?
        if household.pending_item_named(name)
          errors.add(:name, :duplicate_pending, message: "já está na lista")
          next false
        end

        update!(status: :pending, purchased_at: nil)
      else
        update!(status: :purchased, purchased_at: Time.current)
      end
      true
    end
  end

  # Fixes what was typed. A category picked by hand is applied and learned, so
  # the next item with this name lands there; a new name with the category
  # left as it was is recategorized like a freshly typed one. Returns false,
  # with errors, when the name is blank or already pending on the list.
  def correct(name:, category_id:)
    expected_version = lock_version
    household.with_list_lock do
      reload
      raise StaleAction unless lock_version == expected_version
      raise StaleAction unless shopping_trip_id.nil?

      self.name = name.to_s.squish
      picked = Category.find_by(id: category_id) if category_id.present? && category_id.to_i != self.category_id

      if picked
        self.category = picked
      elsif will_save_change_to_name?
        self.category = ItemCategorizer.call(self.name)
      end

      saved = transaction do
        raise ActiveRecord::Rollback unless save

        ItemCategorizer.learn(self.name, picked) if picked
        true
      end
      saved.present?
    end
  end

  private

  # No quantities in this app, so a second pending "leite" adds nothing.
  # Purchased items don't count: buying it again starts a new pending entry.
  def not_already_pending
    return if name.blank? || household.nil?

    errors.add(:name, :duplicate_pending, message: "já está na lista") if household.pending_item_named(name)
  end

  def not_renamed_to_a_pending_item
    other = household.pending_item_named(name)
    errors.add(:name, :duplicate_pending, message: "já está na lista") if other && other != self
  end

  def assign_category
    self.category = ItemCategorizer.call(name)
  end

  def normalize_name
    self.normalized_name = TextNormalizer.call(name)
  end
end
