require "test_helper"
require "support/sync_command_builders"

# The real undo_shopping executor behind the real processor and credentials.
class SyncCommands::UndoShoppingTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    @milk = list_items(:milk)           # pending, stays on the list
    @detergent = list_items(:detergent) # purchased
    @rice = purchase("Arroz")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "brings the trip's items back as they were and deletes the trip, in one revision" do
    original = [ @detergent, @rice ].map { |item| item.reload.slice(:name, :category_id, :status, :purchased_at, :created_at) }
    finish = submit(@ana, finish_shopping_command(*observed(@detergent, @rice))).first
    trip_id = finish[:data]["trip"]["public_id"]
    before = @home.reload.sync_revision

    result = submit(@ana, undo_shopping_command(trip_id)).first

    assert_equal [ "applied", before + 1 ], result.values_at(:status, :revision)
    assert_nil ShoppingTrip.find_by(public_id: trip_id)
    assert_equal original, [ @detergent, @rice ].map { |item| item.reload.slice(:name, :category_id, :status, :purchased_at, :created_at) }
    assert_equal [ nil, nil ], [ @detergent.shopping_trip_id, @rice.shopping_trip_id ]
    assert_equal trip_id, result[:data]["trip_public_id"]
    assert_equal [ "Detergente", "Arroz" ], result[:data]["items"].map { |entry| entry["name"] }
    assert_equal [ "purchased", @detergent.purchased_at.utc.iso8601(6), @detergent.lock_version ],
      result[:data]["items"].first.values_at("status", "purchased_at", "lock_version")
    assert_equal [], result[:data]["dropped"]
    assert_not result[:data].key?("finish_id")
    assert SyncTombstone.exists?(household: @home, public_id: trip_id, record_type: "ShoppingTrip")
  end

  test "finish and undo queued in one batch: the undo references the original operation" do
    finish = finish_shopping_command(*observed(@detergent))
    undo = undo_shopping_command(depends_on: finish["id"])

    results = submit(@ana, finish, undo)

    assert_equal %w[applied applied], results.map { |result| result[:status] }
    assert_equal finish["id"], results.last[:data]["finish_id"]
    assert_equal results.first[:data]["trip"]["public_id"], results.last[:data]["trip_public_id"]
    assert_equal 0, ShoppingTrip.count
    assert_nil @detergent.reload.shopping_trip_id
  end

  test "an undo long after the finish still works while the trip is the latest" do
    finish = submit(@ana, finish_shopping_command(*observed(@detergent))).first

    result = travel(30.days) { submit(@isaac, undo_shopping_command(finish[:data]["trip"]["public_id"])) }.first

    assert_equal "applied", result[:status]
    assert_nil @detergent.reload.shopping_trip_id
  end

  test "only the most recent trip can be undone, and the older one after it" do
    older = submit(@ana, finish_shopping_command(*observed(@detergent))).first[:data]["trip"]["public_id"]
    newer = submit(@ana, finish_shopping_command(*observed(@rice))).first[:data]["trip"]["public_id"]
    revision = @home.reload.sync_revision

    refused = submit(@ana, undo_shopping_command(older)).first

    assert_equal [ "conflict", "precondition_failed", "not_latest", newer ],
      [ refused[:status], refused[:error], refused[:data]["reason"], refused[:data]["latest_trip_public_id"] ]
    assert_equal 2, ShoppingTrip.count
    assert_equal revision, @home.reload.sync_revision
    assert_equal 0, SyncCommand.where(command_type: "undo_shopping").count, "a conflict consumes nothing"

    assert_equal "applied", submit(@ana, undo_shopping_command(newer)).first[:status]
    assert_equal "applied", submit(@ana, undo_shopping_command(older)).first[:status]
    assert_equal 0, ShoppingTrip.count
  end

  test "resending the same undo replays the original receipt without a second effect" do
    trip_id = submit(@ana, finish_shopping_command(*observed(@detergent))).first[:data]["trip"]["public_id"]
    command = undo_shopping_command(trip_id)
    first = submit(@ana, command).first
    revision = @home.reload.sync_revision

    resent = submit(@ana, command).first

    assert_equal [ "duplicate", "applied", first[:revision], first[:data] ],
      [ resent[:status], resent[:original_status], resent[:revision], resent[:data] ]
    assert_equal revision, @home.reload.sync_revision
  end

  test "a trip that was already undone answers gone to another undo; unknown and foreign trips answer not_found" do
    trip_id = submit(@ana, finish_shopping_command(*observed(@detergent))).first[:data]["trip"]["public_id"]
    submit(@ana, undo_shopping_command(trip_id))
    beach = Household.create!(name: "Praia")
    foreign = beach.shopping_trips.create!(finished_at: Time.current)

    again = submit(@isaac, undo_shopping_command(trip_id)).first
    unknown = submit(@ana, undo_shopping_command(uuid)).first
    other_house = submit(@ana, undo_shopping_command(foreign.public_id)).first

    assert_equal [ "rejected", "gone", "undone" ], again.values_at(:status, :error) + [ again[:data]["reason"] ]
    assert_equal [ "rejected", "not_found" ], unknown.values_at(:status, :error)
    assert_equal({ id: other_house[:id], status: "rejected", error: "not_found" }, other_house, "same answer as an unknown trip")
    assert ShoppingTrip.exists?(foreign.id)
  end

  test "an item typed again after the finish stays; the archived copy is dropped and its identity redirected" do
    trip_id = submit(@ana, finish_shopping_command(*observed(@detergent, @rice))).first[:data]["trip"]["public_id"]
    alias_id = uuid
    SyncAlias.create!(household: @home, public_id: alias_id, canonical_public_id: @rice.public_id, command_id: uuid)
    again = @home.add_item("arroz").item # a new pending "Arroz" while the trip stands
    revision = @home.reload.sync_revision

    result = submit(@ana, undo_shopping_command(trip_id)).first

    assert_equal [ "applied", revision + 1 ], result.values_at(:status, :revision)
    assert_equal [ { "public_id" => @rice.public_id, "canonical_public_id" => again.public_id } ], result[:data]["dropped"]
    assert_equal [ @detergent.public_id ], result[:data]["items"].map { |entry| entry["public_id"] }
    assert_not ListItem.exists?(@rice.id), "the archived duplicate is deleted, as in the PWA"
    assert SyncTombstone.exists?(household: @home, public_id: @rice.public_id)
    assert_equal 1, @home.list_items.where(normalized_name: "arroz").count

    # Whoever still holds the dropped identity, or an alias of it, reaches the item that stayed.
    [ @rice.public_id, alias_id ].each do |held|
      marked = submit(@ana, set_item_state_command(held, "purchased", lock_version: again.reload.lock_version)).first
      assert_equal [ "applied", again.public_id ], [ marked[:status], marked[:data]["item"]["canonical_public_id"] ]
      submit(@ana, set_item_state_command(held, "pending", lock_version: again.reload.lock_version))
    end
  end

  test "depends_on must be an applied finish_shopping, and a stated trip must be the same one" do
    finish = finish_shopping_command(*observed(@detergent))
    submit(@ana, finish)
    other = submit(@ana, add_items_command([ uuid, "Tapioca" ])).first
    trip_id = ShoppingTrip.last.public_id

    [ [ undo_shopping_command(depends_on: other[:id]), "depends_on" ],
      [ undo_shopping_command(uuid, depends_on: finish["id"]), "payload.trip_public_id" ],
      [ undo_shopping_command, "payload.trip_public_id" ],
      [ undo_shopping_command("nao-uuid"), "payload.trip_public_id" ] ].each do |command, field|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", field ], [ result[:status], result[:error], result[:data]["field"] ]
    end
    assert_equal "applied", submit(@ana, undo_shopping_command(trip_id, depends_on: finish["id"])).first[:status]
  end

  test "undoing announces one list refresh after the commit and a refusal announces nothing" do
    older = submit(@ana, finish_shopping_command(*observed(@detergent))).first[:data]["trip"]["public_id"]
    newer = submit(@ana, finish_shopping_command(*observed(@rice))).first[:data]["trip"]["public_id"]

    assert_empty capture_turbo_stream_broadcasts(@home) { submit(@ana, undo_shopping_command(older)) }
    streams = capture_turbo_stream_broadcasts(@home) { submit(@ana, undo_shopping_command(newer)) }
    assert_equal 1, streams.count { |stream| stream["target"] == "list_items" }
  end

  test "a failure after the effect keeps the trip and the archive" do
    trip_id = submit(@ana, finish_shopping_command(*observed(@detergent))).first[:data]["trip"]["public_id"]
    failing = Module.new { def create!(*) = raise("falha depois do efeito") }
    SyncCommand.singleton_class.prepend(failing)
    begin
      assert_raises(RuntimeError) { submit(@ana, undo_shopping_command(trip_id)) }
    ensure
      failing.send(:remove_method, :create!)
    end

    assert_equal trip_id, @detergent.reload.shopping_trip.public_id
    assert_equal 0, SyncCommand.where(command_type: "undo_shopping").count
    assert_not SyncTombstone.exists?(public_id: trip_id)
  end

  private

  def purchase(name)
    @home.list_items.create!(name: name).tap { |item| item.toggle_purchased!(sync: false) }
  end

  def observed(*items) = items.map { |item| [ item.public_id, item.reload.lock_version ] }

  def submit(device, *commands)
    SyncCommandProcessor.new(device: device).call(commands)
  end
end
