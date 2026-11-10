# A revision that no household owns. The dictionary (categories and catalog
# items) is shared by every household, so its changes are numbered on their own
# and never mixed with a household's own revision.
class SyncScope < ApplicationRecord
  DICTIONARY = "dictionary".freeze

  validates :name, presence: true, uniqueness: true
  validates :revision, numericality: { only_integer: true, greater_than_or_equal_to: 0 }

  # Advances the scope's revision inside the caller's transaction and returns
  # the new value. The UPDATE is the read and the write at once, so two
  # concurrent connections cannot lose an increment.
  def self.bump!(name)
    scope = find_or_create_by!(name: name)
    uncached do
      where(id: scope.id).update_all("revision = revision + 1")
      scope.revision = where(id: scope.id).pick(:revision)
    end
    scope.revision
  end

  # Zero when the scope was never bumped, like a household that never changed.
  def self.revision_for(name)
    where(name: name).pick(:revision) || 0
  end
end
