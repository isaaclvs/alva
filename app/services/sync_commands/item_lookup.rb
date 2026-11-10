module SyncCommands
  # Finds the item a command refers to by the UUID the phone holds, which is
  # either the item's own public_id or the alias the server registered when it
  # unified two additions of the same name. Everything is scoped to the
  # household of the credential: a UUID that belongs to another household
  # answers exactly like one that never existed.
  class ItemLookup
    # kind: :live (on the list), :archived (history), :removed (tombstone, or an
    # alias whose item is gone) or :unknown. `item` is set for :live/:archived.
    Lookup = Data.define(:kind, :item, :canonical_public_id) do
      def live? = kind == :live
      def gone? = kind.in?(%i[archived removed])
      def unknown? = kind == :unknown
    end

    def initialize(household)
      @household = household
    end

    def find(public_id)
      aliased = SyncAlias.where(household_id: @household.id, public_id: public_id).pick(:canonical_public_id)
      canonical = aliased || public_id
      item = @household.list_items.find_by(public_id: canonical)
      return Lookup.new(item.shopping_trip_id ? :archived : :live, item, canonical) if item

      removed = aliased || SyncTombstone.exists?(household_id: @household.id, public_id: canonical)
      Lookup.new(removed ? :removed : :unknown, nil, canonical)
    end

    # Whether the UUID is already claimed by anything in the shared namespace
    # (items, aliases, tombstones) of any household. Only meaningful for a UUID
    # that find says is unknown to this household: then someone else holds it.
    def self.claimed?(public_id)
      ListItem.exists?(public_id: public_id) || SyncAlias.exists?(public_id: public_id) ||
        SyncTombstone.exists?(public_id: public_id)
    end
  end
end
