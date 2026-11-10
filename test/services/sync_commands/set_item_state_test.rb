require "test_helper"
require "support/sync_command_builders"

# The real set_item_state executor behind the real processor and credentials.
class SyncCommands::SetItemStateTest < ActiveSupport::TestCase
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

  test "marks the item purchased with the server's date and publishes one revision" do
    before = @home.reload.sync_revision

    result = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: @milk.lock_version)).first

    assert_equal [ "applied", before + 1 ], result.values_at(:status, :revision)
    @milk.reload
    assert @milk.purchased?
    assert_in_delta Time.current, @milk.purchased_at, 5
    assert_equal({ "public_id" => @milk.public_id, "canonical_public_id" => @milk.public_id, "name" => "Leite",
      "status" => "purchased", "purchased_at" => @milk.purchased_at.utc.iso8601(6), "lock_version" => @milk.lock_version },
      result[:data]["item"])
    assert_equal 1, @milk.lock_version
  end

  test "unmarking returns the item to pending and clears the purchase date" do
    result = submit(@ana, set_item_state_command(@detergent.public_id, "pending", lock_version: @detergent.lock_version)).first

    assert_equal "applied", result[:status]
    assert @detergent.reload.pending?
    assert_nil @detergent.purchased_at
  end

  test "the desired state is never a toggle: the same request again, or from another phone, changes nothing" do
    version = @milk.lock_version
    command = set_item_state_command(@milk.public_id, "purchased", lock_version: version)
    submit(@ana, command)
    stamped = @milk.reload.purchased_at
    revision = @home.reload.sync_revision

    resent = submit(@ana, command).first
    other_command = submit(@isaac, set_item_state_command(@milk.public_id, "purchased", lock_version: version)).first
    third = submit(@isaac, set_item_state_command(@milk.public_id, "purchased", lock_version: @milk.reload.lock_version)).first

    assert_equal [ "duplicate", "applied" ], resent.values_at(:status, :original_status)
    assert_equal [ "noop", "noop" ], [ other_command[:status], third[:status] ], "already purchased, whatever version the request saw"
    assert_equal [ "purchased", stamped, revision ], [ @milk.reload.status, @milk.purchased_at, @home.reload.sync_revision ]
    assert_equal "purchased", other_command[:data]["item"]["status"]
  end

  test "a stale version is a conflict that carries the current state and overwrites nothing" do
    stale = @milk.lock_version
    @milk.toggle_purchased! # another device bought it
    @milk.toggle_purchased! # and put it back
    revision = @home.reload.sync_revision

    result = submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: stale)).first

    assert_equal [ "conflict", "precondition_failed" ], result.values_at(:status, :error)
    assert_equal "version_changed", result[:data]["reason"]
    assert_equal stale, result[:data]["expected_lock_version"]
    assert_equal [ "pending", @milk.reload.lock_version ], result[:data]["current"].values_at("status", "lock_version")
    assert @milk.pending?
    assert_equal revision, @home.reload.sync_revision
    assert_equal 0, SyncCommand.where(command_type: "set_item_state").count, "a conflict consumes nothing"
  end

  test "an old unmark cannot bring back a purchase someone else just made" do
    stale = @detergent.lock_version # the phone saw it purchased long ago…
    @detergent.toggle_purchased!    # …it was unmarked…
    @detergent.toggle_purchased!    # …and bought again just now
    bought_at = @detergent.reload.purchased_at

    result = submit(@ana, set_item_state_command(@detergent.public_id, "pending", lock_version: stale)).first

    assert_equal [ "conflict", "precondition_failed" ], result.values_at(:status, :error)
    assert_equal [ "purchased", bought_at ], [ @detergent.reload.status, @detergent.purchased_at ]
  end

  test "a version is required when nothing earlier in the chain touched the item" do
    [ set_item_state_command(@milk.public_id, "purchased"),
      set_item_state_command(@milk.public_id, "purchased", lock_version: -1).tap { |c| c["preconditions"]["lock_version"] = "3" } ].each do |command|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", "preconditions.lock_version" ],
        [ result[:status], result[:error], result[:data]["field"] ]
    end
    assert @milk.reload.pending?
  end

  test "a dependency about other items does not lend its versions" do
    other = submit(@ana, add_items_command([ uuid, "Tapioca" ])).first
    dependent = set_item_state_command(@milk.public_id, "purchased", depends_on: other[:id])

    result = submit(@ana, dependent).first

    assert_equal [ "rejected", "malformed_command" ], result.values_at(:status, :error)
  end

  test "add → mark → unmark inherits each version from the step before, in one batch" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])
    unmark = set_item_state_command(item, "pending", depends_on: mark["id"])

    results = submit(@ana, add, mark, unmark)

    assert_equal %w[applied applied applied], results.map { |result| result[:status] }
    assert_equal [ 0, 1, 2 ], [ results[0][:data]["items"].first, results[1][:data]["item"], results[2][:data]["item"] ].map { |entry| entry["lock_version"] }
    created = @home.list_items.find_by!(public_id: item)
    assert created.pending?
    assert_nil created.purchased_at
    assert_equal 2, created.lock_version
    assert_equal 3, results.map { |result| result[:revision] }.uniq.size, "each step published its own revision"
  end

  test "the sequence survives arriving in separate requests and a lost response" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])
    unmark = set_item_state_command(item, "pending", depends_on: mark["id"])

    submit(@ana, add)
    first = submit(@ana, mark).first
    assert_equal "applied", first[:status]
    # the response to `mark` never arrives; the phone sends everything again
    resent = submit(@ana, add, mark, unmark)

    assert_equal %w[duplicate duplicate applied], resent.map { |result| result[:status] }
    assert_equal first[:data], resent[1][:data]
    created = @home.list_items.find_by!(public_id: item)
    assert_equal [ "pending", 2 ], [ created.status, created.lock_version ], "the resend did not flip the item again"
  end

  test "a dependent waits for the command it needs and applies once that one is in" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])

    early = submit(@ana, mark).first
    submit(@ana, add)
    late = submit(@ana, mark).first

    assert_equal [ "blocked", "dependency_not_applied" ], early.values_at(:status, :error)
    assert_equal "applied", late[:status]
  end

  test "another device changing the item in the middle breaks the chain with a conflict" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", depends_on: add["id"])
    unmark = set_item_state_command(item, "pending", depends_on: mark["id"])
    submit(@ana, add)
    created = @home.list_items.find_by!(public_id: item)
    created.toggle_purchased!
    created.toggle_purchased! # Isaac bought and unbought it: now at version 2

    results = submit(@ana, mark, unmark)

    assert_equal %w[conflict blocked], results.map { |result| result[:status] }
    assert_equal [ 0, 2 ], results[0][:data].values_at("expected_lock_version").push(results[0][:data]["current"]["lock_version"])
    assert created.reload.pending?
  end

  test "an explicit version beside an inherited one is redundant and ignored" do
    item = uuid
    add = add_items_command([ item, "Tapioca" ])
    mark = set_item_state_command(item, "purchased", lock_version: 99, depends_on: add["id"])

    results = submit(@ana, add, mark)

    assert_equal %w[applied applied], results.map { |result| result[:status] }
  end

  test "a UUID unified with another phone's item works through its alias, also in a chain" do
    ana_item, isaac_item = uuid, uuid
    submit(@ana, add_items_command([ ana_item, "Tapioca" ]))
    add = add_items_command([ isaac_item, "tapioca" ])
    mark = set_item_state_command(isaac_item, "purchased", depends_on: add["id"])

    results = submit(@isaac, add, mark)

    assert_equal %w[noop applied], results.map { |result| result[:status] }
    assert_equal [ isaac_item, ana_item ], results[1][:data]["item"].values_at("public_id", "canonical_public_id")
    assert_equal "purchased", @home.list_items.find_by!(public_id: ana_item).status
    third = submit(@ana, set_item_state_command(ana_item, "pending", lock_version: 1)).first
    assert_equal "applied", third[:status]
  end

  test "archived and removed items answer gone and are not recreated" do
    removed = list_items(:milk)
    archived_id = @detergent.public_id
    @home.finish_shopping!
    @home.remove_item(removed)

    archived_result = submit(@ana, set_item_state_command(archived_id, "pending", lock_version: 0)).first
    removed_result = submit(@ana, set_item_state_command(removed.public_id, "purchased", lock_version: 0)).first

    assert_equal [ "rejected", "gone", "archived" ], archived_result.values_at(:status, :error).push(archived_result[:data]["reason"])
    assert_equal [ "rejected", "gone", "removed" ], removed_result.values_at(:status, :error).push(removed_result[:data]["reason"])
    assert_equal 0, @home.list_items.on_list.count
    assert @home.list_items.archived.where(public_id: archived_id).exists?
    assert_not ListItem.exists?(public_id: removed.public_id)
  end

  test "an alias whose item was removed answers gone, and unknown or foreign UUIDs answer not_found" do
    alias_id = uuid
    submit(@ana, add_items_command([ alias_id, "leite" ])) # alias of the fixture milk
    @home.remove_item(@milk)
    beach = Household.create!(name: "Praia")
    foreign = beach.list_items.create!(name: "Coco")

    gone = submit(@ana, set_item_state_command(alias_id, "purchased", lock_version: 0)).first
    unknown = submit(@ana, set_item_state_command(uuid, "purchased", lock_version: 0)).first
    other_house = submit(@ana, set_item_state_command(foreign.public_id, "purchased", lock_version: 0)).first

    assert_equal [ "rejected", "gone" ], gone.values_at(:status, :error)
    assert_equal [ { id: unknown[:id], status: "rejected", error: "not_found" } ], [ unknown ]
    assert_equal({ id: other_house[:id], status: "rejected", error: "not_found" }, other_house, "same answer as an unknown UUID")
    assert foreign.reload.pending?
  end

  test "an alias registered by another household is not resolved here" do
    beach = Household.create!(name: "Praia")
    foreign = beach.list_items.create!(name: "Coco")
    alias_id = uuid
    SyncAlias.create!(household: beach, public_id: alias_id, canonical_public_id: foreign.public_id, command_id: uuid)

    result = submit(@ana, set_item_state_command(alias_id, "purchased", lock_version: 0)).first

    assert_equal [ "rejected", "not_found" ], result.values_at(:status, :error)
    assert foreign.reload.pending?
  end

  test "unmarking beside a pending item with the same name is a conflict" do
    @home.list_items.create!(name: "Detergente")

    result = submit(@ana, set_item_state_command(@detergent.public_id, "pending", lock_version: @detergent.lock_version)).first

    assert_equal [ "conflict", "duplicate_pending" ], [ result[:status], result[:data]["reason"] ]
    assert @detergent.reload.purchased?
  end

  test "malformed state requests are rejected with the field" do
    [ [ set_item_state_command("nao-uuid", "purchased", lock_version: 0), "payload.public_id" ],
      [ set_item_state_command(@milk.public_id, "toggle", lock_version: 0), "payload.desired" ],
      [ set_item_state_command(@milk.public_id, nil, lock_version: 0), "payload.desired" ] ].each do |command, field|
      result = submit(@ana, command).first

      assert_equal [ "rejected", "malformed_command", field ], [ result[:status], result[:error], result[:data]["field"] ]
    end
  end

  test "marking announces one list refresh after the commit and a conflict announces nothing" do
    version = @milk.lock_version
    capture_turbo_stream_broadcasts(@home) do
      submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: version))
    end.then { |streams| assert_equal 1, streams.count { |stream| stream["target"] == "list_items" } }

    streams = capture_turbo_stream_broadcasts(@home) do
      submit(@ana, set_item_state_command(@milk.public_id, "pending", lock_version: version))
    end
    assert_empty streams
  end

  test "a failure after the effect leaves the item as it was and no ledger row" do
    @ana.household.define_singleton_method(:record_shared_change!) { |**| raise "falha depois do efeito" }

    streams = capture_turbo_stream_broadcasts(@home) do
      assert_raises(RuntimeError) { submit(@ana, set_item_state_command(@milk.public_id, "purchased", lock_version: @milk.lock_version)) }
    end

    assert_empty streams
    assert_equal [ "pending", 0 ], [ @milk.reload.status, @milk.lock_version ]
    assert_equal 0, SyncCommand.where(command_type: "set_item_state").count
  end

  private

  def submit(device, *commands)
    SyncCommandProcessor.new(device: device).call(commands)
  end
end
