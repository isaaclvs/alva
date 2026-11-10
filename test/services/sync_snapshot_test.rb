require "test_helper"

class SyncSnapshotTest < ActiveSupport::TestCase
  UUID = /\A\h{8}-\h{4}-\h{4}-\h{4}-\h{12}\z/
  TIME = /\A\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z\z/

  ITEM_KEYS = %w[category_public_id created_at lock_version name normalized_name position public_id purchased_at status updated_at]

  setup do
    @home = Household.create!(name: "Casa do teste")
  end

  test "an empty household still yields a complete, versioned document" do
    snapshot = snap

    assert_equal 1, snapshot[:schema_version]
    assert_equal 0, snapshot[:revision]
    assert_equal({ public_id: @home.public_id, name: "Casa do teste" }, snapshot[:household])
    assert_equal [], snapshot[:list_items]
    assert_equal [], snapshot[:archived_items]
    assert_equal [], snapshot[:shopping_trips]
    assert_equal [], snapshot[:habitual_items]
    assert_equal [], snapshot[:tombstones]
    assert_equal Category.count, snapshot[:categories].size
    assert_equal CatalogItem.count, snapshot[:catalog_items].size
  end

  test "every collection exposes exactly its documented fields and no integer id" do
    seed_history
    snapshot = snap

    assert_equal ITEM_KEYS, snapshot[:list_items].first.keys.map(&:to_s).sort
    assert_equal (ITEM_KEYS + %w[shopping_trip_public_id]).sort, snapshot[:archived_items].first.keys.map(&:to_s).sort
    assert_equal %w[name position public_id], snapshot[:categories].first.keys.map(&:to_s).sort
    assert_equal %w[category_public_id learned name normalized_name public_id], snapshot[:catalog_items].first.keys.map(&:to_s).sort
    assert_equal %w[created_at normalized_name name public_id].sort, snapshot[:habitual_items].first.keys.map(&:to_s).sort
    assert_equal %w[created_at finished_at public_id], snapshot[:shopping_trips].first.keys.map(&:to_s).sort
    assert_equal %w[public_id record_type revision], snapshot[:tombstones].first.keys.map(&:to_s).sort

    each_key_and_value(snapshot.deep_stringify_keys) do |key, value|
      assert_not_equal "id", key
      assert key.end_with?("public_id") || !key.end_with?("_id"), "#{key} looks like an internal id"
      assert_match UUID, value if key.end_with?("public_id") && value
      assert_match TIME, value if key.end_with?("_at") && value
    end
  end

  test "timestamps keep microseconds in UTC" do
    item = @home.list_items.create!(name: "Arroz", created_at: Time.utc(2026, 11, 10, 12, 0, 0, 123_456))

    assert_equal "2026-11-10T12:00:00.123456Z", snap[:list_items].first[:created_at]
    assert_equal item.updated_at.utc.iso8601(6), snap[:list_items].first[:updated_at]
  end

  test "the live list and the archive are separate and hold the item state undo needs" do
    pending = @home.list_items.create!(name: "Café")
    bought = @home.list_items.create!(name: "Banana")
    bought.toggle_purchased!
    uncategorized = @home.list_items.create!(name: "Sem dono", category: nil)
    uncategorized.update_columns(category_id: nil)
    trip = @home.finish_shopping!
    snapshot = snap

    assert_equal [ "Café", "Sem dono" ], snapshot[:list_items].map { _1[:name] }
    assert_equal [ "Banana" ], snapshot[:archived_items].map { _1[:name] }
    assert_nil snapshot[:list_items].last[:category_public_id]
    assert_equal categories(:produce).public_id, snapshot[:archived_items].first[:category_public_id]
    assert_equal "pending", snapshot[:list_items].first[:status]

    archived = snapshot[:archived_items].first
    assert_equal "purchased", archived[:status]
    assert_equal trip.public_id, archived[:shopping_trip_public_id]
    assert_equal bought.reload.purchased_at.utc.iso8601(6), archived[:purchased_at]
    assert_equal bought.lock_version, archived[:lock_version]
    assert_equal pending.lock_version, snapshot[:list_items].first[:lock_version]
  end

  test "restoring a removed item and undoing a toggle need nothing the snapshot omits" do
    item = @home.list_items.create!(name: "Leite", position: 3)
    item.toggle_purchased!
    wire = snap[:list_items].first

    snapshot_fields = ListItemSnapshot::ATTRIBUTES - %w[id household_id category_id] + %w[category_public_id lock_version]
    assert_empty snapshot_fields - wire.keys.map(&:to_s)
    item.reload
    assert_equal categories(:dairy).public_id, wire[:category_public_id]
    assert_equal [ item.position, item.status, item.created_at.utc.iso8601(6), item.purchased_at.utc.iso8601(6) ],
      wire.values_at(:position, :status, :created_at, :purchased_at)
    assert_equal item.lock_version, wire[:lock_version]
  end

  test "collections are ordered by ascending internal id" do
    names = %w[Zebra Abacate Mel Uva]
    items = names.map { |name| @home.list_items.create!(name:) }
    habitual = names.map { |name| @home.add_habitual_item(name) }
    items.first.toggle_purchased!
    first_trip = @home.finish_shopping!
    items.last.toggle_purchased!
    second_trip = @home.finish_shopping!

    snapshot = snap

    assert_equal [ items[1], items[2] ].map(&:public_id), snapshot[:list_items].map { _1[:public_id] }
    assert_equal [ items[0], items[3] ].map(&:public_id), snapshot[:archived_items].map { _1[:public_id] }
    assert_equal habitual.map(&:public_id), snapshot[:habitual_items].map { _1[:public_id] }
    assert_equal [ first_trip, second_trip ].map(&:public_id), snapshot[:shopping_trips].map { _1[:public_id] }
    assert_equal Category.order(:position).pluck(:public_id), snapshot[:categories].map { _1[:public_id] }
    assert_equal CatalogItem.order(:id).pluck(:public_id), snapshot[:catalog_items].map { _1[:public_id] }
  end

  test "revision and dictionary revision are the stored ones" do
    @home.add_item("Arroz")
    item = @home.list_items.on_list.first
    item.correct(name: "Arroz", category_id: categories(:dairy).id)

    snapshot = snap

    assert_equal @home.reload.sync_revision, snapshot[:revision]
    assert_equal 2, snapshot[:revision]
    assert_equal SyncScope.revision_for(SyncScope::DICTIONARY), snapshot[:dictionary_revision]
    assert_equal 1, snapshot[:dictionary_revision]
    learned = snapshot[:catalog_items].find { _1[:normalized_name] == "arroz" }
    assert_equal({ name: "Arroz", learned: true, category_public_id: categories(:dairy).public_id },
      learned.slice(:name, :learned, :category_public_id))
  end

  test "habitual items are included and removals leave typed tombstones" do
    keep = @home.add_habitual_item("Café")
    gone = @home.add_habitual_item("Sal")
    @home.remove_habitual_item(gone)
    removed = @home.add_item("Feijão").item
    @home.remove_item(removed)
    bought = @home.add_item("Arroz").item
    bought.toggle_purchased!
    trip = @home.finish_shopping!

    snapshot = snap

    assert_equal [ [ "Café", keep.public_id ] ], snapshot[:habitual_items].map { _1.values_at(:name, :public_id) }
    assert_equal [ [ "habitual_item", gone.public_id ], [ "list_item", removed.public_id ] ],
      snapshot[:tombstones].map { _1.values_at(:record_type, :public_id) }
    assert_equal snapshot[:tombstones].map { _1[:revision] }.sort, snapshot[:tombstones].map { _1[:revision] }
    # Archiving is not a deletion.
    assert_not_includes snapshot[:tombstones].map { _1[:public_id] }, bought.public_id

    # Undoing the trip deletes the trip and the archived copy of a name that is
    # back on the list; both are removals.
    @home.add_item("Arroz")
    @home.undo_shopping!(trip)
    tombs = snap[:tombstones].map { _1.values_at(:record_type, :public_id) }
    assert_includes tombs, [ "shopping_trip", trip.public_id ]
    assert_includes tombs, [ "list_item", bought.public_id ]
  end

  test "an undone shopping trip leaves the archive and a restored item leaves the tombstones" do
    item = @home.add_item("Arroz").item
    item.toggle_purchased!
    trip = @home.finish_shopping!
    assert_equal [ trip.public_id ], snap[:shopping_trips].map { _1[:public_id] }

    @home.undo_shopping!(trip)
    snapshot = snap

    assert_equal [], snapshot[:shopping_trips]
    assert_includes snapshot[:tombstones].map { _1.values_at(:record_type, :public_id) }, [ "shopping_trip", trip.public_id ]
    assert_equal [], snapshot[:archived_items]
    assert_equal [ [ "Arroz", "purchased", nil ] ], snapshot[:list_items].map { _1.values_at(:name, :status, :shopping_trip_public_id) }

    removed = ListItemSnapshot.serialize(item.reload)
    @home.remove_item(item)
    assert_includes snap[:tombstones].map { _1.values_at(:record_type, :public_id) }, [ "list_item", item.public_id ]
    @home.restore_item(removed)
    assert_empty snap[:tombstones].select { _1[:public_id] == item.public_id }
    assert_equal [ item.public_id ], snap[:list_items].map { _1[:public_id] }
  end

  test "the whole history is delivered, so buy-again ranks exactly as the server does" do
    seed_history
    snapshot = snap

    assert_equal ListItem.archived.where(household: @home).count, snapshot[:archived_items].size
    assert_equal @home.shopping_trips.count, snapshot[:shopping_trips].size
    assert_operator snapshot[:shopping_trips].size, :>, BuyAgainSuggestions::LIMIT

    expected = BuyAgainSuggestions.call(@home, limit: 1_000).map do |s|
      [ s.name, s.category&.public_id, s.trips, s.last_purchased_at.utc.iso8601(6) ]
    end

    assert_operator expected.size, :>, BuyAgainSuggestions::LIMIT
    assert_equal expected, client_buy_again(snapshot)
    assert_equal expected.first(BuyAgainSuggestions::LIMIT), client_buy_again(snapshot).first(BuyAgainSuggestions::LIMIT)
  end

  test "the latest trip and its items can be found for undo" do
    seed_history
    snapshot = snap

    trips = snapshot[:shopping_trips]
    latest = trips.each_with_index.max_by { |trip, index| [ trip[:finished_at], index ] }.first

    assert_equal @home.latest_shopping_trip.public_id, latest[:public_id]
    expected_items = @home.latest_shopping_trip.list_items.order(:id).pluck(:public_id)
    assert_equal expected_items, snapshot[:archived_items].select { _1[:shopping_trip_public_id] == latest[:public_id] }.map { _1[:public_id] }
  end

  test "nothing of another household is read" do
    other = Household.create!(name: "Outra")
    other.add_habitual_item("Segredo")
    other_item = other.add_item("Item alheio").item
    other_item.toggle_purchased!
    other.finish_shopping!
    gone = other.add_item("Removido alheio").item
    other.remove_item(gone)
    @home.add_item("Meu")

    document = snap.to_json

    ["Segredo", "Item alheio", "Removido alheio", other.public_id, other_item.public_id, gone.public_id].each do |value|
      assert_not_includes document, value
    end
  end

  test "reading never writes" do
    seed_history
    revision = @home.reload.sync_revision

    assert_no_changes -> { [ ListItem.maximum(:updated_at), SyncTombstone.count, @home.reload.sync_revision ] } do
      snap
      snap
    end
    assert_equal revision, @home.sync_revision
  end

  private

  def snap
    SyncSnapshot.call(@home)
  end

  def each_key_and_value(node, &block)
    case node
    when Hash then node.each { |key, value| value.is_a?(Enumerable) ? each_key_and_value(value, &block) : block.call(key, value) }
    when Array then node.each { |value| each_key_and_value(value, &block) }
    end
  end

  # Six trips and a live list with repeated names, different spellings,
  # categories that changed, a legacy row without purchased_at, spellings bought
  # at the very same instant and names that tie on everything but the name, so
  # every ranking rule is exercised.
  def seed_history
    clock = Time.utc(2026, 10, 1, 12, 0, 0, 500_000)
    new_trip = ->(days_ago) { @home.shopping_trips.create!(finished_at: clock - days_ago.days) }
    buy = lambda do |name, trip, at: nil, category: nil|
      item = @home.list_items.new(name:, status: :purchased, purchased_at: at || (trip.finished_at - 1.hour), shopping_trip: trip)
      item.category = category if category
      item.save!
      item
    end

    first = new_trip.(20)
    buy.("Leite", first)
    buy.("Pão", first)
    buy.("Arroz", first)
    second = new_trip.(14)
    buy.("leite", second, category: categories(:other))
    buy.("Pão", second)
    buy.("Café", second)
    third = new_trip.(9)
    buy.("LEITE", third)
    buy.("Leite integral", third)
    buy.("Mel", third).update_columns(purchased_at: nil, updated_at: third.finished_at)
    fourth = new_trip.(4)
    buy.("Mel Silvestre", fourth)
    fifth = new_trip.(3)
    tie = fifth.finished_at
    buy.("Mamão", fifth, at: tie)
    buy.("Chá", fifth, at: tie)
    buy.("chá", fifth, at: tie)
    sixth = new_trip.(1)
    buy.("mamao", sixth, at: tie)
    buy.("Uva", sixth, at: tie)
    buy.("Uva", sixth, at: tie)
    buy.("Banana", sixth, at: tie)
    buy.("Abacate", sixth, at: tie)

    @home.add_item("Pão")
    @home.add_habitual_item("Sal")
    @home.remove_item(@home.add_item("Provisório").item)
  end

  # What the Kotlin client will do with the snapshot: group the archive by
  # normalized name, count distinct trips, pick the latest row (ties go to the
  # later one in the document) and rank by trips, recency and name.
  def client_buy_again(snapshot)
    on_list = snapshot[:list_items].map { _1[:normalized_name] }
    rows = snapshot[:archived_items].reject { _1[:normalized_name].blank? || on_list.include?(_1[:normalized_name]) }

    suggestions = rows.group_by { _1[:normalized_name] }.values.map do |group|
      latest, = group.each_with_index.max_by { |row, index| [ row[:purchased_at] || row[:updated_at], index ] }
      [ latest[:name], latest[:category_public_id], group.map { _1[:shopping_trip_public_id] }.uniq.size, latest[:purchased_at] || latest[:updated_at] ]
    end
    suggestions.sort { |a, b| [ -a[2], b[3], a[0] ] <=> [ -b[2], a[3], b[0] ] }
  end
end
