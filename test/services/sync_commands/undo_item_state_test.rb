require "test_helper"
require "support/sync_command_builders"

# The real undo_item_state executor behind the real processor and credentials.
class SyncCommands::UndoItemStateTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    @milk = list_items(:milk)
    @detergent = list_items(:detergent)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "undoing a mark restores pending and clears the date, with the version the mark left" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    marked_version = mark[:data]["item"]["lock_version"]
    before = @home.reload.sync_revision

    result = submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: marked_version)).first

    assert_equal [ "applied", before + 1 ], result.values_at(:status, :revision)
    @milk.reload
    assert @milk.pending?
    assert_nil @milk.purchased_at
    assert_equal({ "public_id" => @milk.public_id, "canonical_public_id" => @milk.public_id, "name" => "Leite",
      "status" => "pending", "purchased_at" => nil, "lock_version" => @milk.lock_version }, result[:data]["item"])
    assert_equal 2, @milk.lock_version
  end

  test "undoing an unmark brings back the original purchase date" do
    original = @detergent.purchased_at
    unmark = submit(@ana, set_item_state_command(@detergent.public_id, "pending", lock_version: 0)).first
    assert_equal "applied", unmark[:status]

    result = submit(@ana, undo_item_state_command(@detergent.public_id, "purchased",
      purchased_at: original.utc.iso8601(6), lock_version: unmark[:data]["item"]["lock_version"])).first

    assert_equal "applied", result[:status]
    @detergent.reload
    assert @detergent.purchased?
    assert_equal original.utc.iso8601(6), @detergent.purchased_at.utc.iso8601(6)
    assert_equal original.utc.iso8601(6), result[:data]["item"]["purchased_at"]
  end

  test "mark and undo in one batch inherit the version from the mark" do
    mark = set_item_state_command(@milk.public_id, "purchased", lock_version: 0)
    undo = undo_item_state_command(@milk.public_id, "pending", depends_on: mark["id"])

    results = submit(@ana, mark, undo)

    assert_equal %w[applied applied], results.map { |result| result[:status] }
    assert_equal [ 1, 2 ], results.map { |result| result[:data]["item"]["lock_version"] }
    assert @milk.reload.pending?
  end

  test "resending the same undo answers duplicate without a second effect, even after the item changed" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    command = undo_item_state_command(@milk.public_id, "pending", lock_version: mark[:data]["item"]["lock_version"])
    first = submit(@ana, command).first
    @milk.reload.toggle_purchased! # bought again meanwhile
    revision = @home.reload.sync_revision
    stamped = @milk.reload.purchased_at

    resent = submit(@ana, command).first

    assert_equal [ "duplicate", "applied", first[:revision], first[:data] ],
      [ resent[:status], resent[:original_status], resent[:revision], resent[:data] ]
    assert_equal [ "purchased", stamped, revision ], [ @milk.reload.status, @milk.purchased_at, @home.reload.sync_revision ]
    assert_equal 1, SyncCommand.where(command_type: "undo_item_state").count
  end

  test "another undo of the same mark with a new id is a conflict, not a second restore" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    version = mark[:data]["item"]["lock_version"]
    submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: version))
    @milk.reload.toggle_purchased! # bought again, a later purchase the undo must not erase
    bought_at = @milk.reload.purchased_at

    result = submit(@isaac, undo_item_state_command(@milk.public_id, "pending", lock_version: version)).first

    assert_equal [ "conflict", "precondition_failed", "version_changed" ],
      [ result[:status], result[:error], result[:data]["reason"] ]
    assert_equal version, result[:data]["expected_lock_version"]
    assert_equal [ "purchased", bought_at ], [ @milk.reload.status, @milk.purchased_at ]
  end

  test "an undo that finds the item already restored is a noop whatever version it carries" do
    revision = @home.reload.sync_revision

    result = submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: 9)).first

    assert_equal "noop", result[:status]
    assert_equal [ "pending", 0 ], result[:data]["item"].values_at("status", "lock_version")
    assert_equal revision, @home.reload.sync_revision
    assert_equal 1, SyncCommand.where(command_type: "undo_item_state").count, "a noop is consumed so a resend replays it"
  end

  test "a later edit by another device wins: the undo conflicts and overwrites nothing" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    version = mark[:data]["item"]["lock_version"]
    @milk.reload.update!(name: "Leite integral") # another device renamed it (bumps lock_version)
    revision = @home.reload.sync_revision

    result = submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: version)).first

    assert_equal [ "conflict", "precondition_failed" ], result.values_at(:status, :error)
    assert_equal "Leite integral", result[:data]["current"]["name"]
    assert @milk.reload.purchased?
    assert_equal revision, @home.reload.sync_revision
    assert_equal 0, SyncCommand.where(command_type: "undo_item_state").count, "a conflict consumes nothing"
  end

  test "restoring pending beside a pending item with the same name is a conflict, as in the PWA" do
    marked = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    @home.list_items.create!(name: "Leite") # typed again after the mark

    result = submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: marked[:data]["item"]["lock_version"])).first

    assert_equal [ "conflict", "duplicate_pending" ], [ result[:status], result[:data]["reason"] ]
    assert @milk.reload.purchased?
  end

  test "an archived or removed item is gone and nothing is recreated" do
    @milk.toggle_purchased!
    @home.finish_shopping!
    removed = @home.list_items.create!(name: "Tapioca")
    @home.remove_item(removed)

    archived_result = submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: @milk.reload.lock_version)).first
    removed_result = submit(@ana, undo_item_state_command(removed.public_id, "pending", lock_version: 0)).first

    assert_equal [ "rejected", "gone", "archived" ], archived_result.values_at(:status, :error).push(archived_result[:data]["reason"])
    assert_equal [ "rejected", "gone", "removed" ], removed_result.values_at(:status, :error).push(removed_result[:data]["reason"])
    assert @milk.reload.purchased?
    assert_not ListItem.exists?(public_id: removed.public_id)
  end

  test "unknown and foreign UUIDs answer the same not_found" do
    beach = Household.create!(name: "Praia")
    foreign = beach.list_items.create!(name: "Coco")
    foreign.toggle_purchased!

    unknown = submit(@ana, undo_item_state_command(uuid, "pending", lock_version: 0)).first
    other_house = submit(@ana, undo_item_state_command(foreign.public_id, "pending", lock_version: 1)).first

    assert_equal [ "rejected", "not_found" ], unknown.values_at(:status, :error)
    assert_equal({ id: other_house[:id], status: "rejected", error: "not_found" }, other_house)
    assert foreign.reload.purchased?
  end

  test "an alias of the item is resolved" do
    alias_id = uuid
    submit(@ana, add_items_command([ alias_id, "leite" ])) # alias of the fixture milk
    mark = submit(@ana, set_item_state_command(alias_id, "purchased", lock_version: 0)).first

    result = submit(@ana, undo_item_state_command(alias_id, "pending", lock_version: mark[:data]["item"]["lock_version"])).first

    assert_equal "applied", result[:status]
    assert_equal [ alias_id, @milk.public_id ], result[:data]["item"].values_at("public_id", "canonical_public_id")
    assert @milk.reload.pending?
  end

  test "a version is required when nothing earlier in the chain touched the item" do
    bad_version = undo_item_state_command(@milk.public_id, "pending", lock_version: 1).tap { |c| c["preconditions"]["lock_version"] = "1" }
    other = submit(@ana, add_items_command([ uuid, "Tapioca" ])).first

    [ undo_item_state_command(@milk.public_id, "purchased"), bad_version,
      undo_item_state_command(@milk.public_id, "purchased", depends_on: other[:id]) ].each do |command|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", "preconditions.lock_version" ],
        [ result[:status], result[:error], result[:data]["field"] ]
    end
    assert @milk.reload.pending?
  end

  test "malformed payloads are rejected with the field" do
    stamp = 1.hour.ago.utc.iso8601(6)
    [ [ undo_item_state_command("nao-uuid", "pending", lock_version: 0), "payload.public_id" ],
      [ undo_item_state_command(@milk.public_id, "toggle", lock_version: 0), "payload.status" ],
      [ undo_item_state_command(@milk.public_id, nil, lock_version: 0), "payload.status" ],
      [ undo_item_state_command(@detergent.public_id, "purchased", purchased_at: "ontem", lock_version: 0), "payload.purchased_at" ],
      [ undo_item_state_command(@detergent.public_id, "purchased", purchased_at: 12, lock_version: 0), "payload.purchased_at" ],
      [ undo_item_state_command(@milk.public_id, "pending", purchased_at: stamp, lock_version: 0), "payload.purchased_at" ] ].each do |command, field|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", field ], [ result[:status], result[:error], result[:data]["field"] ]
    end
  end

  test "undoing announces one list refresh after the commit and a conflict announces nothing" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    version = mark[:data]["item"]["lock_version"]

    streams = capture_turbo_stream_broadcasts(@home) do
      submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: version))
    end
    assert_equal 1, streams.count { |stream| stream["target"] == "list_items" }

    @milk.reload.toggle_purchased!
    streams = capture_turbo_stream_broadcasts(@home) do
      submit(@ana, undo_item_state_command(@milk.public_id, "pending", lock_version: version))
    end
    assert_empty streams
  end

  test "a failure after the effect leaves the item as it was and no ledger row" do
    mark = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: 0)).first
    marked = @milk.reload.purchased_at
    command = undo_item_state_command(@milk.public_id, "pending", lock_version: mark[:data]["item"]["lock_version"])

    # The ledger row is the last write of the transaction: its failure undoes the item too.
    failing = Module.new { def create!(*) = raise("falha depois do efeito") }
    SyncCommand.singleton_class.prepend(failing)
    begin
      assert_raises(RuntimeError) { submit(@ana, command) }
    ensure
      # prepend cannot be undone; neutralize it for later tests
      failing.send(:remove_method, :create!)
    end

    assert_equal [ "purchased", marked, 1 ], [ @milk.reload.status, @milk.purchased_at, @milk.lock_version ]
    assert_equal 0, SyncCommand.where(command_type: "undo_item_state").count
  end

  private

  def submit(device, *commands)
    SyncCommandProcessor.new(device: device).call(commands)
  end
end
