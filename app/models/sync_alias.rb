# The UUID a phone generated offline for an item that the server unified with an
# existing canonical one (another phone added the same name first, or the name
# matched a purchased item that came back). Commands that cite the alias are
# resolved to the canonical item. Rows are never pruned: see "Retenção" in
# docs/plans/android-sync-contract-v1.md.
class SyncAlias < ApplicationRecord
  belongs_to :household

  validates :public_id, :canonical_public_id, :command_id, presence: true
  validates :public_id, format: { with: SyncCommand::UUID }
  validate :not_its_own_canonical

  private

  def not_its_own_canonical
    errors.add(:canonical_public_id, :invalid) if public_id == canonical_public_id
  end
end
