require "test_helper"
require "support/sync_command_builders"

class SyncCommands::RemoveItemTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = households(:home)
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @milk = list_items(:milk)
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest }

  test "removes with an undo-ready receipt, tombstone and one revision" do
    revision = @home.sync_revision
    result = submit(remove(@milk.public_id, @milk.lock_version)).first

    assert_equal "applied", result[:status]
    assert_equal revision + 1, result[:revision]
    assert_not ListItem.exists?(@milk.id)
    assert_equal @milk.public_id, SyncTombstone.find_by!(public_id: @milk.public_id).public_id
    item = result.dig(:data, "item")
    assert_equal [ @milk.public_id, "Leite", @milk.category_id, "pending", @milk.lock_version ],
      item.values_at("canonical_public_id", "name", "category_id", "status", "lock_version")
    assert item.key?("created_at")
    assert item.key?("position")
    assert SyncCommand.exists?(command_id: result[:id], status: "applied")
  end

  test "inherits the version through add and resolves aliases" do
    local_id = uuid
    add = add_items_command([ local_id, "leite" ])
    removal = remove(local_id, nil, depends_on: add["id"])
    results = submit(add, removal)
    assert_equal %w[noop applied], results.map { |entry| entry[:status] }
    assert_not ListItem.exists?(public_id: @milk.public_id)
  end

  test "rejects malformed versions and conflicts on stale item" do
    [ -1, "0", false ].each do |version|
      assert_equal "malformed_command", submit(remove(@milk.public_id, version)).first[:error]
    end
    @milk.update!(name: "Leite integral")
    result = submit(remove(@milk.public_id, 0)).first
    assert_equal [ "conflict", "precondition_failed" ], result.values_at(:status, :error)
    assert @milk.reload
  end

  test "unknown, foreign, archived and removed items cannot be removed" do
    beach = Household.create!(name: "Praia")
    foreign = beach.list_items.create!(name: "Coco")
    assert_equal "not_found", submit(remove(foreign.public_id, 0)).first[:error]
    @milk.update!(status: "purchased")
    @home.finish_shopping!
    assert_equal "gone", submit(remove(@milk.public_id, @milk.lock_version)).first[:error]
  end

  test "replay is duplicate and ledger failure rolls back deletion and tombstone" do
    command = remove(@milk.public_id, @milk.lock_version)
    submit(command)
    assert_equal "duplicate", submit(command).first[:status]

    item = @home.list_items.create!(name: "Farinha")
    @ana.household.define_singleton_method(:record_shared_change!) { |**| raise "ledger rollback" }
    assert_raises(RuntimeError) { submit(remove(item.public_id, item.lock_version)) }
    assert item.reload
    assert_not SyncTombstone.exists?(public_id: item.public_id)
  end

  private

  def remove(public_id, version, id: uuid, depends_on: nil)
    { "id" => id, "type" => "remove_item", "depends_on" => depends_on,
      "payload" => { "public_id" => public_id }, "preconditions" => { "lock_version" => version } }
  end

  def submit(*commands) = SyncCommandProcessor.new(device: @ana).call(commands)
end
