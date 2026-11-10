# The complete synchronized state of one household, as served by
# GET /api/v1/snapshot (see docs/plans/android-sync-contract-v1.md, "Snapshot").
#
# Everything is read inside a single deferred SQLite transaction. In WAL mode
# the first read fixes the snapshot the transaction sees, so a writer that
# commits halfway through cannot leak into the document: `revision` and every
# collection describe the same committed state, and the read never blocks (or
# is blocked by) writers. Rails' own transactions are IMMEDIATE, which would
# queue behind every write; `joinable: false` is what asks for a deferred one.
#
# Fields are listed explicitly instead of calling as_json on the models, so a
# new column (a digest, a token) can never leak into the wire format by
# accident. Integer ids stay internal: records refer to each other by
# `public_id`. Every collection is ordered by ascending internal id, which is
# part of the contract: it is the tie-breaker the Rails rankings use (latest
# trip, latest spelling), and clients have no other way to reproduce it.
class SyncSnapshot
  SCHEMA_VERSION = 1

  # Wire names for SyncTombstone#record_type, independent of Ruby class names.
  TOMBSTONE_TYPES = {
    "ListItem" => "list_item",
    "HabitualItem" => "habitual_item",
    "ShoppingTrip" => "shopping_trip"
  }.freeze

  def self.call(household)
    new(household).call
  end

  def initialize(household)
    @household_id = household.id
  end

  def call
    Household.transaction(joinable: false) do
      # The revision goes first: it is the read that fixes the snapshot.
      household = Household.where(id: @household_id).pick(:public_id, :name, :sync_revision)
      categories = Category.order(:position, :id).pluck(:id, :public_id, :name, :position)
      @category_ids = categories.to_h { |id, public_id, *| [ id, public_id ] }
      trips = ShoppingTrip.where(household_id: @household_id).order(:id).pluck(:id, :public_id, :finished_at, :created_at)
      @trip_ids = trips.to_h { |id, public_id, *| [ id, public_id ] }

      {
        schema_version: SCHEMA_VERSION,
        revision: household.fetch(2),
        dictionary_revision: SyncScope.revision_for(SyncScope::DICTIONARY),
        household: { public_id: household.fetch(0), name: household.fetch(1) },
        categories: categories.map { |_, public_id, name, position| { public_id:, name:, position: } },
        catalog_items: catalog_items,
        list_items: items(ListItem.on_list),
        archived_items: items(ListItem.archived, archived: true),
        shopping_trips: trips.map { |_, public_id, finished_at, created_at| { public_id:, finished_at: time(finished_at), created_at: time(created_at) } },
        habitual_items: habitual_items,
        tombstones: tombstones
      }
    end
  end

  private

  def catalog_items
    CatalogItem.order(:id).pluck(:public_id, :name, :normalized_name, :category_id, :learned)
      .map do |public_id, name, normalized_name, category_id, learned|
        { public_id:, name:, normalized_name:, category_public_id: @category_ids.fetch(category_id), learned: }
      end
  end

  # The same fields for live and archived items: an archived item is a live one
  # that left the list with a trip, and undoing the trip brings it back as is.
  def items(scope, archived: false)
    columns = %i[public_id name normalized_name category_id status position purchased_at created_at updated_at lock_version]
    columns << :shopping_trip_id if archived

    scope.where(household_id: @household_id).order(:id).pluck(*columns).map do |row|
      public_id, name, normalized_name, category_id, status, position, purchased_at, created_at, updated_at, lock_version, trip_id = row
      item = {
        public_id:, name:, normalized_name:,
        category_public_id: category_id && @category_ids.fetch(category_id),
        status:, position:, purchased_at: time(purchased_at), created_at: time(created_at), updated_at: time(updated_at),
        lock_version:
      }
      item[:shopping_trip_public_id] = @trip_ids.fetch(trip_id) if archived
      item
    end
  end

  def habitual_items
    HabitualItem.where(household_id: @household_id).order(:id).pluck(:public_id, :name, :normalized_name, :created_at)
      .map { |public_id, name, normalized_name, created_at| { public_id:, name:, normalized_name:, created_at: time(created_at) } }
  end

  def tombstones
    SyncTombstone.where(household_id: @household_id, scope: SyncTombstone::HOUSEHOLD).order(:revision, :id)
      .pluck(:record_type, :public_id, :revision)
      .map { |record_type, public_id, revision| { record_type: TOMBSTONE_TYPES.fetch(record_type, record_type.underscore), public_id:, revision: } }
  end

  # Microseconds, like ListItemSnapshot: items created in the same second stay
  # in order.
  def time(value)
    value&.utc&.iso8601(6)
  end
end
