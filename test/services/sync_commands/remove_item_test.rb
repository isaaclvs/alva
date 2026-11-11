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
    assert_equal [ @milk.public_id, "Leite", @milk.category.public_id, "pending", @milk.lock_version ],
      item.values_at("canonical_public_id", "name", "category_public_id", "status", "lock_version")
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
    failing = remove(item.public_id, item.lock_version)
    before_revision = @home.reload.sync_revision
    db = ActiveRecord::Base.connection
    db.execute(<<~SQL)
      CREATE TEMP TRIGGER fail_removal_ledger BEFORE INSERT ON sync_commands
      WHEN NEW.command_id = '#{failing["id"]}'
      BEGIN SELECT RAISE(ABORT, 'ledger unavailable'); END
    SQL
    assert_raises(ActiveRecord::StatementInvalid) { submit(failing) }
    assert item.reload
    assert_equal before_revision, @home.reload.sync_revision
    assert_not SyncTombstone.exists?(public_id: item.public_id)
    assert_not SyncCommand.exists?(command_id: failing["id"])
  ensure
    db&.execute("DROP TRIGGER IF EXISTS fail_removal_ledger")
  end

  test "malformed item ids are rejected before lookup" do
    [ nil, false, 7, [], {}, "", "not-a-uuid" ].each do |value|
      result = submit(remove(value, 0)).first
      assert_equal "malformed_command", result[:error]
      assert_equal "payload.public_id", result[:data]["field"]
    end
    assert ListItem.exists?(@milk.id)
  end

  test "fresh addition can be removed through inherited version and gone has no second effect" do
    local = uuid
    added = add_items_command([ local, "Farinha" ])
    removal = remove(local, nil, depends_on: added["id"])
    assert_equal %w[applied applied], submit(added, removal).map { |result| result[:status] }
    before_revision = @home.reload.sync_revision
    result = submit(remove(local, 0)).first
    assert_equal "gone", result[:error]
    assert_equal before_revision, @home.reload.sync_revision
    assert_equal "not_found", submit(remove(uuid, 0)).first[:error]
  end

  private

  def remove(public_id, version, id: uuid, depends_on: nil)
    { "id" => id, "type" => "remove_item", "depends_on" => depends_on,
      "payload" => { "public_id" => public_id }, "preconditions" => { "lock_version" => version } }
  end

  def submit(*commands) = SyncCommandProcessor.new(device: @ana).call(commands)
end
