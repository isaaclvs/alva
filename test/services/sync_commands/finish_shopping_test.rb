require "test_helper"
require "support/sync_command_builders"

# The real finish_shopping executor behind the real processor and credentials.
class SyncCommands::FinishShoppingTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    @milk = list_items(:milk)           # pending
    @detergent = list_items(:detergent) # purchased
    @rice = purchase("Arroz")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "archives exactly the observed purchased items into one trip and leaves the rest alone" do
    before = @home.reload.sync_revision
    kept_at = @detergent.purchased_at.utc.iso8601(6)

    result = submit(@ana, finish_shopping_command(*observed(@detergent, @rice))).first

    assert_equal [ "applied", before + 1 ], result.values_at(:status, :revision)
    trip = ShoppingTrip.find_by!(public_id: result[:data]["trip"]["public_id"])
    assert_equal trip.finished_at.utc.iso8601(6), result[:data]["trip"]["finished_at"]
    assert_equal [ trip, trip ], [ @detergent.reload.shopping_trip, @rice.reload.shopping_trip ]
    assert_nil @milk.reload.shopping_trip, "a pending item stays on the list"
    assert_equal [ "Detergente", "purchased", kept_at ], [ @detergent.name, @detergent.status, @detergent.purchased_at.utc.iso8601(6) ]
    assert_equal categories(:dairy), @milk.category
    assert_equal [ "Detergente", "Arroz" ], result[:data]["items"].map { |entry| entry["name"] }
    assert_equal trip.public_id, result[:data]["items"].first["shopping_trip_public_id"]
    # update_all bumps the optimistic lock too: a stale command from before the trip conflicts.
    assert_equal [ @detergent.public_id, @detergent.public_id, "purchased", 1 ],
      result[:data]["items"].first.values_at("public_id", "canonical_public_id", "status", "lock_version")
  end

  test "a purchased item that appeared after the device looked is not archived" do
    observed_items = observed(@detergent)
    later = purchase("Café") # bought on another phone after this one's snapshot

    result = submit(@ana, finish_shopping_command(*observed_items)).first

    assert_equal "applied", result[:status]
    assert_nil later.reload.shopping_trip
    assert later.purchased?
    assert_equal [ @rice, later ], @home.list_items.on_list.purchased.order(:id).to_a
  end

  test "any failed precondition archives nothing, creates no trip and lists every item to review" do
    stale = observed(@detergent, @rice)
    @rice.toggle_purchased!(sync: false) # unmarked elsewhere (version 1)
    @detergent.reload.update!(name: "Detergente neutro") # renamed elsewhere
    revision = @home.reload.sync_revision

    assert_no_difference "ShoppingTrip.count" do
      result = submit(@ana, finish_shopping_command(*stale)).first

      assert_equal [ "conflict", "precondition_failed", "version_changed", 0 ],
        [ result[:status], result[:error], result[:data]["reason"], result[:data]["index"] ]
      assert_equal [ 0, 1 ], result[:data]["problems"].map { |problem| problem["index"] }
      assert_equal "Detergente neutro", result[:data]["problems"].first["current"]["name"]
      assert_equal [ "pending", 2 ], result[:data]["problems"].last["current"].values_at("status", "lock_version")
    end

    assert_nil @detergent.reload.shopping_trip
    assert_equal revision, @home.reload.sync_revision
    assert_equal 0, SyncCommand.where(command_type: "finish_shopping").count, "a conflict consumes nothing"
  end

  test "an item sent as purchased that is pending at the same version is refused" do
    result = submit(@ana, finish_shopping_command([ @milk.public_id, @milk.lock_version ])).first

    assert_equal [ "conflict", "not_purchased" ], [ result[:status], result[:data]["reason"] ]
    assert_nil @milk.reload.shopping_trip
    assert_equal 0, ShoppingTrip.count
  end

  test "versions can be inherited from the commands that marked the items in the same batch" do
    mark = set_item_state_command(@milk.public_id, "purchased", lock_version: 0)
    finish = finish_shopping_command([ @milk.public_id, nil ], depends_on: mark["id"])

    results = submit(@ana, mark, finish)

    assert_equal %w[applied applied], results.map { |result| result[:status] }
    assert_equal @milk.reload.shopping_trip.public_id, results.last[:data]["trip"]["public_id"]
  end

  test "a version is required for an item the dependency did not touch" do
    other = submit(@ana, add_items_command([ uuid, "Tapioca" ])).first

    [ finish_shopping_command([ @detergent.public_id, nil ]),
      finish_shopping_command([ @detergent.public_id, nil ], depends_on: other[:id]) ].each do |command|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command" ], result.values_at(:status, :error)
      assert_equal "payload.items[0].lock_version", result[:data]["problems"].first["field"]
    end
    assert_equal 0, ShoppingTrip.count
  end

  test "resending the same command replays the original receipt, also after the trip was undone" do
    command = finish_shopping_command(*observed(@detergent))
    first = submit(@ana, command).first
    revision = @home.reload.sync_revision

    resent = submit(@ana, command).first
    @home.undo_shopping!(ShoppingTrip.last)
    late = submit(@ana, command).first

    assert_equal [ "duplicate", "applied", first[:revision], first[:data] ],
      [ resent[:status], resent[:original_status], resent[:revision], resent[:data] ]
    assert_equal first[:data], late[:data]
    assert_equal revision + 1, @home.reload.sync_revision, "only the undo moved the revision"
    assert_equal 0, ShoppingTrip.count
    assert_equal 1, SyncCommand.where(command_id: command["id"]).count
  end

  test "the same items under another id find them archived: no second or empty trip" do
    submit(@ana, finish_shopping_command(*observed(@detergent, @rice)))
    revision = @home.reload.sync_revision

    result = submit(@isaac, finish_shopping_command([ @detergent.public_id, 0 ], [ @rice.public_id, 0 ])).first

    assert_equal [ "rejected", "gone", "archived" ], result.values_at(:status, :error) + [ result[:data]["reason"] ]
    assert_equal 1, ShoppingTrip.count
    assert_equal revision, @home.reload.sync_revision
  end

  test "an item removed or never known fails the whole command" do
    removed = purchase("Sal")
    stale = observed(@detergent, removed)
    @home.remove_item(removed)

    gone = submit(@ana, finish_shopping_command(*stale)).first
    unknown = submit(@ana, finish_shopping_command([ uuid, 0 ])).first

    assert_equal [ "rejected", "gone", "removed", 1 ], gone.values_at(:status, :error) + [ gone[:data]["reason"], gone[:data]["index"] ]
    assert_equal [ "rejected", "not_found" ], unknown.values_at(:status, :error)
    assert_equal 0, ShoppingTrip.count
    assert_nil @detergent.reload.shopping_trip
  end

  test "an item of another household answers like an unknown one" do
    beach = Household.create!(name: "Praia")
    foreign = beach.list_items.create!(name: "Coco")
    foreign.toggle_purchased!

    result = submit(@ana, finish_shopping_command([ foreign.public_id, foreign.reload.lock_version ])).first
    unknown = submit(@ana, finish_shopping_command([ uuid, 1 ])).first

    assert_equal [ "rejected", "not_found" ], result.values_at(:status, :error)
    assert_equal unknown[:data].except("public_id", "problems"), result[:data].except("public_id", "problems"), "same answer as an unknown UUID"
    assert_nil foreign.reload.shopping_trip
  end

  test "an item cited by its alias is archived, and citing it twice is malformed" do
    alias_item = uuid
    SyncAlias.create!(household: @home, public_id: alias_item, canonical_public_id: @rice.public_id, command_id: uuid)
    version = @rice.lock_version

    twice = submit(@ana, finish_shopping_command([ alias_item, version ], [ @rice.public_id, version ])).first
    result = submit(@ana, finish_shopping_command([ alias_item, version ])).first

    assert_equal [ "rejected", "malformed_command", "repeated_item" ], [ twice[:status], twice[:error], twice[:data]["reason"] ]
    assert_equal "applied", result[:status]
    assert_equal [ alias_item, @rice.public_id ], result[:data]["items"].first.values_at("public_id", "canonical_public_id")
    assert @rice.reload.shopping_trip
  end

  test "malformed payloads are rejected with the field" do
    too_many = Array.new(SyncCommands::FinishShopping::MAX_ITEMS + 1) { [ uuid, 0 ] }
    [ [ finish_shopping_command, "payload.items" ],
      [ finish_shopping_command(*too_many), "payload.items" ],
      [ finish_shopping_command([ "nao-uuid", 0 ]), "payload.items[0].public_id" ],
      [ finish_shopping_command([ @detergent.public_id, 0 ], [ @rice.public_id, "1" ]), "payload.items[1].lock_version" ],
      [ finish_shopping_command([ @detergent.public_id, -1 ]), "payload.items[0].lock_version" ],
      [ { "id" => uuid, "type" => "finish_shopping", "payload" => { "items" => [ "x" ] } }, "payload.items[0]" ],
      [ { "id" => uuid, "type" => "finish_shopping", "payload" => { "items" => "x" } }, "payload.items" ] ].each do |command, field|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", field ], [ result[:status], result[:error], result[:data]["field"] ],
        command["payload"].inspect
    end
    assert_equal 0, ShoppingTrip.count
  end

  test "finishing announces one list refresh after the commit and a refusal announces nothing" do
    stale = observed(@detergent)

    streams = capture_turbo_stream_broadcasts(@home) { submit(@ana, finish_shopping_command(*stale)) }
    assert_equal 1, streams.count { |stream| stream["target"] == "list_items" }

    other = purchase("Sal")
    stale = observed(other)
    other.toggle_purchased!(sync: false)
    streams = capture_turbo_stream_broadcasts(@home) { submit(@ana, finish_shopping_command(*stale)) }
    assert_empty streams
  end

  test "a failure after the effect leaves the items on the list and no trip or ledger row" do
    command = finish_shopping_command(*observed(@detergent))
    failing = Module.new { def create!(*) = raise("falha depois do efeito") }
    SyncCommand.singleton_class.prepend(failing)
    begin
      assert_raises(RuntimeError) { submit(@ana, command) }
    ensure
      failing.send(:remove_method, :create!)
    end

    assert_nil @detergent.reload.shopping_trip
    assert_equal 0, ShoppingTrip.count
    assert_equal 0, SyncCommand.where(command_type: "finish_shopping").count
  end

  private

  def purchase(name)
    @home.list_items.create!(name: name).tap { |item| item.toggle_purchased!(sync: false) }
  end

  # [[public_id, lock_version], ...] as a phone would read them from its snapshot.
  def observed(*items) = items.map { |item| [ item.public_id, item.reload.lock_version ] }

  def submit(device, *commands)
    SyncCommandProcessor.new(device: device).call(commands)
  end
end
