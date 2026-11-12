require "test_helper"
require "support/sync_command_builders"

# create_habitual, remove_habitual and add_habituals behind the real processor.
class SyncCommands::HabitualCommandsTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @beach = Household.create!(name: "Praia")
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest }

  test "create saves the habitual with one revision and reports its UUID" do
    revision = @home.reload.sync_revision

    result = submit(create("  Café  ")).first

    assert_equal "applied", result[:status]
    assert_equal revision + 1, result[:revision]
    habitual = @home.habitual_items.sole
    assert_equal "Café", habitual.name
    assert_equal [ "created", habitual.public_id, "Café", "cafe" ],
      [ result.dig(:data, "outcome"), *result.dig(:data, "item").values_at("public_id", "name", "normalized_name") ]
  end

  test "create of a name already saved is a noop that points at the existing habitual" do
    existing = @home.add_habitual_item("Café")
    revision = @home.reload.sync_revision

    result = submit(create(" cafe ")).first

    assert_equal "noop", result[:status]
    assert_equal revision, result[:revision]
    assert_equal [ "duplicate", existing.public_id ], [ result.dig(:data, "outcome"), result.dig(:data, "item", "public_id") ]
    assert_equal 1, @home.habitual_items.count
  end

  test "the same name in another household does not collide" do
    @beach.add_habitual_item("Café")

    assert_equal "applied", submit(create("Café")).first[:status]
    assert_equal 1, @home.habitual_items.count
  end

  test "create rejects blank and too long names, and a name that is not a string" do
    result = submit(create("   ")).first
    assert_equal [ "rejected", "invalid_name", "blank" ], [ result[:status], result[:error], result.dig(:data, "reason") ]

    result = submit(create("a" * 101)).first
    assert_equal [ "invalid_name", "too_long" ], [ result[:error], result.dig(:data, "reason") ]
    assert_equal "applied", submit(create("a" * 100)).first[:status]

    [ nil, 7, [], {} ].each do |name|
      result = submit(create(name)).first
      assert_equal [ "malformed_command", "payload.name" ], [ result[:error], result.dig(:data, "field") ]
    end
    assert_equal 1, @home.habitual_items.count
  end

  test "create is idempotent by command UUID, even if the habitual was removed meanwhile" do
    command = create("Café")
    original = submit(command).first
    @home.remove_habitual_item(@home.habitual_items.sole)

    replay = submit(command).first

    assert_equal "duplicate", replay[:status]
    assert_equal original[:data], replay[:data]
    assert_equal 0, @home.habitual_items.count
  end

  test "remove deletes only the habitual, leaves a tombstone and keeps the list and history" do
    habitual = @home.add_habitual_item("Arroz")
    archived = @home.list_items.create!(name: "Arroz", status: :purchased, purchased_at: 1.day.ago)
    @home.finish_shopping!
    revision = @home.reload.sync_revision

    result = submit(remove(habitual.public_id)).first

    assert_equal "applied", result[:status]
    assert_equal revision + 1, result[:revision]
    assert_equal [ "removed", "Arroz" ], [ result.dig(:data, "outcome"), result.dig(:data, "item", "name") ]
    assert_not HabitualItem.exists?(habitual.id)
    assert SyncTombstone.exists?(public_id: habitual.public_id, record_type: "HabitualItem", household_id: @home.id)
    assert ListItem.exists?(archived.id)
    assert_equal "Leite", list_items(:milk).reload.name
  end

  test "remove of an already removed habitual is a noop and unknown or foreign UUIDs are not_found" do
    habitual = @home.add_habitual_item("Leite")
    foreign = @beach.add_habitual_item("Coco")
    item = list_items(:milk)
    submit(remove(habitual.public_id))
    revision = @home.reload.sync_revision

    again = submit(remove(habitual.public_id)).first
    assert_equal [ "noop", "already_removed", revision ], [ again[:status], again.dig(:data, "outcome"), again[:revision] ]

    [ uuid, foreign.public_id, item.public_id ].each do |public_id|
      result = submit(remove(public_id)).first
      assert_equal [ "rejected", "not_found" ], [ result[:status], result[:error] ], public_id
    end
    assert HabitualItem.exists?(foreign.id)
    assert ListItem.exists?(item.id)
  end

  test "remove of a habitual another household removed is not_found, not already_removed" do
    foreign = @beach.add_habitual_item("Coco")
    @beach.remove_habitual_item(foreign)

    assert_equal "not_found", submit(remove(foreign.public_id)).first[:error]
  end

  test "remove rejects a malformed UUID" do
    [ nil, 7, "", "not-a-uuid", [], {} ].each do |value|
      result = submit(remove(value)).first
      assert_equal [ "malformed_command", "payload.public_id" ], [ result[:error], result.dig(:data, "field") ]
    end
  end

  test "add selected puts the chosen names on the list like the PWA, in name order" do
    coffee = @home.add_habitual_item("Café")
    bread = @home.add_habitual_item("Pão")
    @home.add_habitual_item("Açúcar")
    revision = @home.reload.sync_revision

    result = submit(add_habituals(public_ids: [ bread.public_id.upcase, coffee.public_id, bread.public_id ])).first

    assert_equal "applied", result[:status]
    assert_equal revision + 1, result[:revision], "one revision for the whole batch"
    entries = result.dig(:data, "items")
    assert_equal %w[Café Pão], entries.map { |entry| entry["name"] }
    assert_equal %w[created created], entries.map { |entry| entry["outcome"] }
    assert_equal [ coffee.public_id, bread.public_id ], entries.map { |entry| entry["habitual_public_id"] }
    created = @home.pending_item_named("Pão")
    assert_equal [ created.public_id, created.public_id ], entries.last.values_at("public_id", "canonical_public_id")
    assert_nil @home.pending_item_named("Açúcar")
    assert_equal 3, @home.habitual_items.count
  end

  test "add all has no 30-name limit and names already pending are duplicates" do
    35.times { |index| @home.add_habitual_item("Habito #{format('%02d', index)}") }
    @home.add_habitual_item("Leite")

    result = submit(add_habituals(all: true)).first

    assert_equal "applied", result[:status]
    outcomes = result.dig(:data, "items").map { |entry| entry["outcome"] }.tally
    assert_equal({ "created" => 35, "duplicate" => 1 }, outcomes)
    assert_equal 1, @home.list_items.on_list.where(normalized_name: "leite").count
  end

  test "a purchased item of that name comes back pending, as in the PWA" do
    habitual = @home.add_habitual_item("Detergente")

    result = submit(add_habituals(public_ids: [ habitual.public_id ])).first

    assert_equal "applied", result[:status]
    entry = result.dig(:data, "items").sole
    assert_equal [ "revived", list_items(:detergent).public_id, "pending" ],
      entry.values_at("outcome", "canonical_public_id", "status")
    assert list_items(:detergent).reload.pending?
  end

  test "when every name is already pending it is a noop with no revision" do
    habitual = @home.add_habitual_item("Leite")
    revision = @home.reload.sync_revision

    result = submit(add_habituals(public_ids: [ habitual.public_id ])).first

    assert_equal [ "noop", revision ], [ result[:status], result[:revision] ]
    assert_equal "duplicate", result.dig(:data, "items").sole["outcome"]
  end

  test "a selection with a UUID that is not this household's habitual adds nothing" do
    mine = @home.add_habitual_item("Café")
    foreign = @beach.add_habitual_item("Coco")
    removed = @home.add_habitual_item("Pão")
    @home.remove_habitual_item(removed)
    revision = @home.reload.sync_revision

    [ foreign.public_id, removed.public_id, uuid ].each do |bad|
      result = submit(add_habituals(public_ids: [ mine.public_id, bad ])).first
      assert_equal [ "rejected", "not_found", [ bad ] ], [ result[:status], result[:error], result.dig(:data, "public_ids") ]
    end
    assert_nil @home.pending_item_named("Café")
    assert_equal revision, @home.reload.sync_revision
  end

  test "add all with no habitual saved is rejected" do
    result = submit(add_habituals(all: true)).first

    assert_equal [ "rejected", "no_habituals" ], [ result[:status], result[:error] ]
    assert_empty SyncCommand.where(command_id: result[:id])
  end

  test "add_habituals needs exactly one well formed selector" do
    habitual = @home.add_habitual_item("Café")
    {
      {} => "payload",
      { all: true, public_ids: [ habitual.public_id ] } => "payload",
      { all: false } => "payload.all",
      { all: "true" } => "payload.all",
      { public_ids: [] } => "payload.public_ids",
      { public_ids: "x" } => "payload.public_ids",
      { public_ids: [ "nope" ] } => "payload.public_ids",
      { public_ids: [ habitual.public_id, 3 ] } => "payload.public_ids"
    }.each do |payload, field|
      result = submit(add_habituals(**payload)).first
      assert_equal [ "malformed_command", field ], [ result[:error], result.dig(:data, "field") ], payload.inspect
    end
    assert_nil @home.pending_item_named("Café")
  end

  test "add is idempotent by command UUID: the resend answers the original and adds nothing" do
    habitual = @home.add_habitual_item("Café")
    command = add_habituals(public_ids: [ habitual.public_id ])
    original = submit(command).first
    @home.list_items.find_by!(normalized_name: "cafe").destroy!
    revision = @home.reload.sync_revision

    replay = submit(command).first

    assert_equal [ "duplicate", "applied", original[:data] ], [ replay[:status], replay[:original_status], replay[:data] ]
    assert_equal revision, @home.reload.sync_revision
    assert_nil @home.pending_item_named("Café")
  end

  test "create and remove broadcast the habits section once, after the commit" do
    streams = capture_turbo_stream_broadcasts(@home) { submit(create("Café")) }
    assert_equal [ %w[replace habitual_items_list] ], streams.map { |stream| [ stream["action"], stream["target"] ] }

    habitual = @home.habitual_items.sole
    streams = capture_turbo_stream_broadcasts(@home) { submit(remove(habitual.public_id)) }
    assert_equal [ %w[replace habitual_items_list] ], streams.map { |stream| [ stream["action"], stream["target"] ] }

    assert_empty capture_turbo_stream_broadcasts(@home) { submit(remove(habitual.public_id)) }, "a noop announces nothing"
    assert_empty capture_turbo_stream_broadcasts(@home) { submit(create("   ")) }
  end

  test "add broadcasts the list once" do
    habitual = @home.add_habitual_item("Café")
    other = @home.add_habitual_item("Pão")

    streams = assert_list_broadcast(@home) { submit(add_habituals(public_ids: [ habitual.public_id, other.public_id ])) }

    assert_includes streams[1].to_html, "Pão"
  end

  test "a failure after the effects leaves no habitual, ledger row or broadcast" do
    @ana.household.define_singleton_method(:record_shared_change!) { |**| raise "falha depois do efeito" }

    streams = capture_turbo_stream_broadcasts(@home) do
      assert_raises(RuntimeError) { submit(create("Café")) }
    end

    assert_empty streams
    assert_equal 0, @home.habitual_items.count
    assert_equal 0, SyncCommand.where(command_type: "create_habitual").count
  end

  test "rolled back add leaves the list as it was" do
    habitual = @home.add_habitual_item("Café")
    @ana.household.define_singleton_method(:record_shared_change!) { |**| raise "falha depois do efeito" }

    assert_raises(RuntimeError) { submit(add_habituals(public_ids: [ habitual.public_id ])) }
    assert_nil @home.pending_item_named("Café")
  end

  private

  def create(name, id: uuid)
    { "id" => id, "type" => "create_habitual", "payload" => { "name" => name } }
  end

  def remove(public_id, id: uuid)
    { "id" => id, "type" => "remove_habitual", "payload" => { "public_id" => public_id } }
  end

  def add_habituals(id: uuid, **payload)
    { "id" => id, "type" => "add_habituals", "payload" => payload.stringify_keys }
  end

  def submit(*commands) = SyncCommandProcessor.new(device: @ana).call(commands)
end
