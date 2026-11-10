# Categories and the catalog dictionary belong to every household, so they
# keep a revision of their own. Because a household snapshot includes them, a
# dictionary change also moves each household's revision; otherwise a client
# comparing only that revision would keep an outdated catalog.
module DictionaryChange
  module_function

  def bump_revision!
    SyncScope.bump!(SyncScope::DICTIONARY)
  end

  # For changes made outside a household operation (db:seed on every deploy):
  # the dictionary revision and every household move together, or they would
  # disagree about which catalog version is current.
  def record!
    ActiveRecord::Base.transaction do
      revision = bump_revision!
      Household.find_each(&:bump_sync_revision!)
      revision
    end
  end
end
