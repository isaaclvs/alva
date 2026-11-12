require "test_helper"
require "support/sync_command_executors"

# The processor with real executors: effects go through Household, the ledger
# row shares their transaction, and the credential is a real AndroidDevice.
class SyncCommandProcessorTest < ActiveSupport::TestCase
  Outcome = SyncCommandProcessor::Outcome

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @device, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @executors = { "add_items" => SyncCommandExecutors::ADD_ITEMS }
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
  end

  test "applies a command and records its identity, content and result with the effect" do
    id = SecureRandom.uuid
    before = @home.reload.sync_revision

    results = submit(command(id, names: [ "Tapioca" ]))

    assert_equal 1, results.size
    assert_equal({ id:, status: "applied", revision: before + 1,
      data: { "items" => [ { "name" => "Tapioca", "outcome" => "created" } ] } }, results.first)
    assert @home.list_items.on_list.pending.exists?(name: "Tapioca")

    record = SyncCommand.find_by!(household: @home, command_id: id)
    assert_equal @device, record.android_device
    assert_equal @device.lineage_id, record.lineage_id
    assert_equal "add_items", record.command_type
    assert_equal "applied", record.status
    assert_equal before + 1, record.revision
    assert_equal results.first[:data], record.data
    assert_equal SyncCommand.content_digest(type: "add_items", depends_on: nil, observed_revision: nil,
      preconditions: {}, payload: { "names" => [ "Tapioca" ] }), record.content_digest
    assert_equal before + 1, @home.reload.sync_revision
  end

  test "an identical replay returns the original result without a second effect" do
    id = SecureRandom.uuid
    original = submit(command(id, names: [ "Tapioca" ])).first
    revision = @home.reload.sync_revision

    replay = submit(command(id, names: [ "Tapioca" ])).first

    assert_equal({ id:, status: "duplicate", original_status: "applied", revision: original[:revision], data: original[:data] },
      replay)
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal revision, @home.reload.sync_revision
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "a replay keeps the original noop even after the state changed" do
    submit(command(SecureRandom.uuid, names: [ "Tapioca" ]))
    id = SecureRandom.uuid
    noop = submit(command(id, names: [ "Tapioca" ])).first
    assert_equal "noop", noop[:status]

    @home.remove_item(@home.pending_item_named("Tapioca"))
    revision = @home.reload.sync_revision
    replay = submit(command(id, names: [ "Tapioca" ])).first

    assert_equal "duplicate", replay[:status]
    assert_equal "noop", replay[:original_status]
    assert_nil @home.pending_item_named("Tapioca"), "a replay must not redo what the first run skipped"
    assert_equal revision, @home.reload.sync_revision
  end

  test "the same id with different content is rejected and changes nothing" do
    id = SecureRandom.uuid
    original = submit(command(id, names: [ "Tapioca" ])).first
    revision = @home.reload.sync_revision
    other = SecureRandom.uuid

    [
      command(id, names: [ "Polvilho" ]),
      command(id, names: [ "Tapioca" ]).merge("type" => "other_type"),
      command(id, names: [ "Tapioca" ]).merge("depends_on" => other),
      command(id, names: [ "Tapioca" ]).merge("preconditions" => { "lock_version" => 3 }),
      command(id, names: [ "Tapioca" ]).merge("observed_revision" => 99)
    ].each do |changed|
      result = submit(changed, executors: @executors.merge("other_type" => SyncCommandExecutors::ADD_ITEMS)).first
      assert_equal({ id:, status: "rejected", error: "idempotency_conflict" }, result, changed.inspect)
    end

    assert_nil @home.pending_item_named("Polvilho")
    assert_equal revision, @home.reload.sync_revision
    record = SyncCommand.find_by!(command_id: id)
    assert_equal original[:data], record.data
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "key order, symbol keys and the envelope revision do not change the content" do
    id = SecureRandom.uuid
    submit(command(id, names: [ "Tapioca" ]).merge("preconditions" => { "a" => 1, "b" => { "x" => 1, "y" => 2 } }), observed_revision: 5)

    reordered = { payload: { names: [ "Tapioca" ] }, preconditions: { b: { y: 2, x: 1 }, a: 1 }, type: "add_items", id: id }
    result = submit(reordered, observed_revision: 9).first

    assert_equal "duplicate", result[:status]
  end

  test "array order is part of the content" do
    id = SecureRandom.uuid
    submit(command(id, names: [ "Tapioca", "Polvilho" ]))

    assert_equal "idempotency_conflict", submit(command(id, names: [ "Polvilho", "Tapioca" ])).first[:error]
  end

  test "an exception rolls back the effect, the revision and the ledger and leaves the command retryable" do
    id = SecureRandom.uuid
    before = @home.reload.sync_revision
    flaky = { "add_items" => SyncCommandExecutors.failing_then_adding(1) }

    assert_raises(RuntimeError) { submit(command(id, names: [ "Tapioca" ]), executors: flaky) }

    assert_nil @home.pending_item_named("Tapioca")
    assert_equal before, @home.reload.sync_revision
    assert_not SyncCommand.exists?(command_id: id)

    result = submit(command(id, names: [ "Tapioca" ]), executors: flaky).first
    assert_equal "applied", result[:status]
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal before + 1, @home.reload.sync_revision
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "an exception keeps the commands that already committed and replays them on retry" do
    first = SecureRandom.uuid
    second = SecureRandom.uuid
    batch = [ command(first, names: [ "Tapioca" ]), command(second, type: "later", names: [ "Polvilho" ]) ]
    failing = @executors.merge("later" => ->(_) { raise "boom" })

    assert_raises(RuntimeError) { submit(batch, executors: failing) }
    assert SyncCommand.exists?(command_id: first)
    assert_not SyncCommand.exists?(command_id: second)

    results = submit(batch, executors: @executors.merge("later" => SyncCommandExecutors::ADD_ITEMS))

    assert_equal %w[duplicate applied], results.map { |r| r[:status] }
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, @home.list_items.where(name: "Polvilho").count
  end

  test "a conflict or a rejection consumes nothing and its writes are rolled back" do
    id = SecureRandom.uuid
    before = @home.reload.sync_revision

    conflict = submit(command(id, names: [ "Tapioca" ]), executors: { "add_items" => SyncCommandExecutors::CONFLICT }).first
    assert_equal({ id:, status: "conflict", error: "precondition_failed", data: { "current" => "outro estado" } }, conflict)

    rejected = submit(command(id, names: [ "Tapioca" ]), executors: { "add_items" => SyncCommandExecutors::REJECT }).first
    assert_equal({ id:, status: "rejected", error: "invalid_name" }, rejected)

    assert_nil @home.pending_item_named("Tapioca")
    assert_equal before, @home.reload.sync_revision
    assert_not SyncCommand.exists?(command_id: id)

    # Not consumed: the same id can still carry a corrected intention.
    assert_equal "applied", submit(command(id, names: [ "Polvilho" ])).first[:status]
  end

  test "results follow the order sent and the effects run in that order" do
    ran = []
    ids = 3.times.map { SecureRandom.uuid }
    results = submit(ids.each_with_index.map { |id, index| command(id, names: [ "Item #{index}" ]) },
      executors: { "add_items" => SyncCommandExecutors.counting(ran) })

    assert_equal ids, results.map { |r| r[:id] }
    assert_equal ids, ran
    assert_equal [ 3 ], [ SyncCommand.where(command_id: ids).count ]
  end

  test "a dependent waits for its dependency in the same lineage" do
    first = SecureRandom.uuid
    second = SecureRandom.uuid

    results = submit([ command(first, names: [ "Tapioca" ]), command(second, names: [ "Polvilho" ]).merge("depends_on" => first) ])

    assert_equal %w[applied applied], results.map { |r| r[:status] }
  end

  test "a dependent is blocked without effect while its dependency has no row, and resolves when it does" do
    dependency = SecureRandom.uuid
    dependent = SecureRandom.uuid
    blocked = command(dependent, names: [ "Polvilho" ]).merge("depends_on" => dependency)
    before = @home.reload.sync_revision

    result = submit(blocked).first
    assert_equal({ id: dependent, status: "blocked", error: "dependency_not_applied", depends_on: dependency }, result)
    assert_nil @home.pending_item_named("Polvilho")
    assert_equal before, @home.reload.sync_revision
    assert_not SyncCommand.exists?(command_id: dependent)

    # Out of order inside one batch is the same: the server applies in the order sent.
    results = submit([ blocked, command(dependency, names: [ "Tapioca" ]) ])
    assert_equal %w[blocked applied], results.map { |r| r[:status] }

    assert_equal "applied", submit(blocked).first[:status]
    assert @home.pending_item_named("Polvilho")
  end

  test "a dependent of a conflicted command is blocked, not applied" do
    dependency = SecureRandom.uuid
    dependent = SecureRandom.uuid
    executors = { "add_items" => SyncCommandExecutors::ADD_ITEMS, "conflicting" => SyncCommandExecutors::CONFLICT }

    results = submit([ command(dependency, type: "conflicting", names: [ "Tapioca" ]),
                    command(dependent, names: [ "Polvilho" ]).merge("depends_on" => dependency) ], executors:)

    assert_equal %w[conflict blocked], results.map { |r| r[:status] }
    assert_nil @home.pending_item_named("Polvilho")
  end

  test "a command cannot depend on another lineage's command or on itself" do
    ana_command = SecureRandom.uuid
    submit(command(ana_command, names: [ "Tapioca" ]))
    isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    own = SecureRandom.uuid

    foreign = submit(command(SecureRandom.uuid, names: [ "Polvilho" ]).merge("depends_on" => ana_command), device: isaac).first
    assert_equal "blocked", foreign[:status]
    assert_nil @home.pending_item_named("Polvilho")

    self_dependent = submit(command(own, names: [ "Polvilho" ]).merge("depends_on" => own)).first
    assert_equal({ id: own, status: "rejected", error: "malformed_command" }, self_dependent)
  end

  test "malformed commands are rejected without effect or ledger row" do
    id = SecureRandom.uuid
    valid = command(id, names: [ "Tapioca" ])
    [
      valid.merge("id" => "nao-e-uuid"),
      valid.except("id"),
      valid.except("type"),
      valid.merge("type" => "Add Items"),
      valid.merge("payload" => [ "Tapioca" ]),
      valid.merge("preconditions" => "x"),
      valid.merge("depends_on" => "nao-e-uuid"),
      valid.merge("depends_on" => false),
      valid.merge("observed_revision" => -1),
      valid.merge("observed_revision" => "10"),
      "texto"
    ].each do |raw|
      result = submit(raw).first
      assert_equal "rejected", result[:status], raw.inspect
      assert_equal "malformed_command", result[:error], raw.inspect
    end

    assert_equal 0, SyncCommand.count
    assert_nil @home.pending_item_named("Tapioca")
    assert_equal id, submit(valid.merge("type" => "x y")).first[:id]
  end

  test "an unknown type is unsupported, but a recorded command still replays without its executor" do
    unknown = submit(command(SecureRandom.uuid).merge("type" => "future_command")).first
    assert_equal "unsupported_command", unknown[:error]
    assert_equal 0, SyncCommand.count

    id = SecureRandom.uuid
    submit(command(id, names: [ "Tapioca" ]))
    assert_equal "duplicate", submit(command(id, names: [ "Tapioca" ]), executors: {}).first[:status]
  end

  test "the production registry has only the commands that were implemented" do
    assert_equal %w[add_items correct_item finish_shopping remove_item set_item_state undo_item_state undo_remove], SyncCommandProcessor::EXECUTORS.keys.sort
    not_yet = command(SecureRandom.uuid, type: "undo_shopping")
    assert_equal "unsupported_command", SyncCommandProcessor.new(device: @device).call([ not_yet ]).first[:error]
  end

  test "an executor sees the ledger row of its dependency, and only once it was applied" do
    seen = []
    executors = { "add_items" => SyncCommandExecutors::ADD_ITEMS,
                  "probe" => lambda { |cmd| seen << cmd.dependency; Outcome.noop } }
    first = SecureRandom.uuid

    submit([ command(SecureRandom.uuid, type: "probe").merge("depends_on" => first) ], executors:)
    submit(command(first), executors:)
    submit([ command(SecureRandom.uuid, type: "probe").merge("depends_on" => first),
             command(SecureRandom.uuid, type: "probe") ], executors:)

    assert_equal [ SyncCommand.find_by!(command_id: first), nil ], seen
    assert_equal first, seen.first.command_id
  end

  test "the list is broadcast once per applied command, after it committed" do
    streams = assert_list_broadcast(@home) { submit(command(SecureRandom.uuid, names: [ "Tapioca", "Polvilho" ])) }
    assert_includes streams[1].to_html, "Polvilho"

    assert_empty capture_turbo_stream_broadcasts(@home) { submit(command(SecureRandom.uuid, type: "conflict"), executors: { "conflict" => SyncCommandExecutors::CONFLICT }) }
  end

  test "the same id in another household is an independent command" do
    id = SecureRandom.uuid
    beach = Household.create!(name: "Praia")
    beach_device, = AndroidDevice.issue(household: beach, name: "Praia", scope: AndroidDevice::SYNC)

    submit(command(id, names: [ "Tapioca" ]))
    result = submit(command(id, names: [ "Tapioca" ]), device: beach_device).first

    assert_equal "applied", result[:status]
    assert_equal [ @home.id, beach.id ].sort, SyncCommand.where(command_id: id).pluck(:household_id).sort
    assert_equal 1, beach.list_items.where(name: "Tapioca").count
  end

  test "another phone cannot replay or take over a command, and learns nothing about it" do
    id = SecureRandom.uuid
    submit(command(id, names: [ "Tapioca" ]))
    isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)

    result = submit(command(id, names: [ "Tapioca" ]), device: isaac).first

    assert_equal({ id:, status: "rejected", error: "idempotency_conflict" }, result)
    record = SyncCommand.find_by!(command_id: id)
    assert_equal @device, record.android_device
    assert_equal @device.lineage_id, record.lineage_id
  end

  test "a re-authorized phone resolves its earlier commands without duplicating them" do
    id = SecureRandom.uuid
    dependent = SecureRandom.uuid
    original = submit(command(id, names: [ "Tapioca" ])).first # the response is lost
    @device.revoke!
    revision = @home.reload.sync_revision

    reauthorized, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC, predecessor: @device)
    results = submit([ command(id, names: [ "Tapioca" ]), command(dependent, names: [ "Polvilho" ]).merge("depends_on" => id) ],
      device: reauthorized)

    assert_equal "duplicate", results.first[:status]
    assert_equal original[:data], results.first[:data]
    assert_equal "applied", results.last[:status], "a command may depend on one its predecessor credential sent"
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal revision + 1, @home.reload.sync_revision
    assert_equal @device, SyncCommand.find_by!(command_id: id).android_device, "the ledger keeps the credential that executed it"
    assert_equal reauthorized, SyncCommand.find_by!(command_id: dependent).android_device
    assert_equal @device.lineage_id, SyncCommand.find_by!(command_id: dependent).lineage_id
  end

  test "a phone re-authorized without its previous secret cannot claim the earlier commands" do
    id = SecureRandom.uuid
    submit(command(id, names: [ "Tapioca" ]))
    stranger, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)

    assert_equal "idempotency_conflict", submit(command(id, names: [ "Tapioca" ]), device: stranger).first[:error]
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
  end

  test "commands are retained: a replay years later still returns the original" do
    id = SecureRandom.uuid
    original = submit(command(id, names: [ "Tapioca" ])).first

    travel_to 5.years.from_now do
      replay = submit(command(id, names: [ "Tapioca" ])).first
      assert_equal "duplicate", replay[:status]
      assert_equal original[:data], replay[:data]
    end
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
  end

  test "only a sync credential may run commands" do
    widget, = AndroidDevice.issue(household: @home, name: "Widget")

    assert_raises(ArgumentError) { SyncCommandProcessor.new(device: widget) }
    assert_raises(ArgumentError) { SyncCommandProcessor.new(device: nil) }
  end

  test "an executor outcome must be one of the known statuses" do
    assert_raises(ArgumentError) { Outcome.new(status: :maybe, data: {}, error: nil) }
    assert_raises(ArgumentError) { Outcome.applied("not a hash") }
  end

  private

  def command(id = SecureRandom.uuid, type: "add_items", names: [ "Tapioca" ])
    { "id" => id, "type" => type, "payload" => { "names" => names } }
  end

  def submit(commands, device: @device, executors: @executors, observed_revision: nil)
    SyncCommandProcessor.new(device:, executors:).call(commands.is_a?(Array) ? commands : [ commands ], observed_revision:)
  end
end
