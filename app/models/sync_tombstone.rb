# A record that left a synchronized set, kept so a client that still holds the
# old copy learns it is gone. Archiving a list item is not a deletion: it stays
# in the history. Only an explicit removal — or the removal of a duplicate made
# by undoing a trip — leaves a tombstone.
class SyncTombstone < ApplicationRecord
  HOUSEHOLD = "household".freeze

  belongs_to :household, optional: true

  validates :record_type, :public_id, :scope, :revision, presence: true

  # Called inside the same transaction as the deletion, with the revision the
  # deletion belongs to.
  def self.record!(record, household:, revision:)
    tombstone = find_or_initialize_by(public_id: record.public_id)
    tombstone.update!(record_type: record.class.name, scope: HOUSEHOLD,
      household_id: household.id, revision: revision)
    tombstone
  end

  # An explicit restore brings the record back: the tombstone goes away, or the
  # same public_id would be both active and removed.
  def self.forget!(public_id)
    where(public_id: public_id).delete_all
  end
end
