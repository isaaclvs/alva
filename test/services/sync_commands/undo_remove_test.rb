require "test_helper"
require "support/sync_command_builders"

class SyncCommands::UndoRemoveTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @home = households(:home)
    @device, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @milk = list_items(:milk)
  end

  teardown { ENV["ALVA_PASSWORD_DIGEST"] = @old_digest }

  test "restores the original attributes after a long delay without consulting the dictionary" do
    @milk.update!(status: :purchased, purchased_at: 3.days.ago, position: 7)
    original = @milk.attributes
    removal = remove(@milk)
    submit(removal)
    revision = @home.reload.sync_revision
    travel 30.days do
      ItemCategorizer.learn(@milk.name, categories(:produce))
      result = submit(undo(removal)).first
      assert_equal "applied", result[:status]
      assert_equal revision + 1, result[:revision]
      restored = @home.list_items.find_by!(public_id: @milk.public_id)
      %w[name normalized_name category_id status purchased_at created_at updated_at position].each do |field|
        assert_equal original[field], restored.attributes[field], field
      end
      assert_equal original["lock_version"] + 1, restored.lock_version
      assert_equal @milk.category.public_id, result.dig(:data, "item", "category_public_id")
      assert_equal removal["id"], result.dig(:data, "removal_id")
      assert_not SyncTombstone.exists?(public_id: @milk.public_id)
    end
  end

  test "lost response replays the receipt without overwriting a later correction" do
    removal = remove(@milk)
    submit(removal)
    command = undo(removal)
    original = submit(command).first
    restored = @home.list_items.find_by!(public_id: @milk.public_id)
    assert restored.correct(name: "Leite remoto", category_id: nil)
    revision = @home.reload.sync_revision
    replay = submit(command).first
    assert_equal "duplicate", replay[:status]
    assert_equal original[:data], replay[:data]
    assert_equal original[:revision], replay[:revision]
    assert_equal "Leite remoto", restored.reload.name
    assert_equal revision, @home.reload.sync_revision
    assert_equal 1, SyncCommand.where(command_id: command["id"]).count
  end

  test "aliases survive and dependent commands inherit a new version while old edits conflict" do
    alias_id = uuid
    submit(add_items_command([ alias_id, "Leite" ]))
    removal = remove(@milk, public_id: alias_id)
    restoration = undo(removal)
    result = submit(removal, restoration).last
    assert_equal "applied", result[:status]
    assert_equal alias_id, result.dig(:data, "item", "public_id")
    assert_equal @milk.public_id, result.dig(:data, "item", "canonical_public_id")
    assert_equal "conflict", submit(correct_item_command(alias_id, "Antigo", lock_version: @milk.lock_version)).first[:status]
    marked = submit(set_item_state_command(alias_id, "purchased", depends_on: restoration["id"])).first
    assert_equal "applied", marked[:status]
    assert_equal "purchased", @home.list_items.find_by!(public_id: @milk.public_id).status
  end

  test "a second undo cannot overwrite live changed or archived items" do
    removal = remove(@milk)
    submit(removal, undo(removal))
    restored = @home.list_items.find_by!(public_id: @milk.public_id)
    assert restored.correct(name: "Leite novo", category_id: nil)
    assert_refused(undo(removal), "item_present")
    restored.toggle_purchased!
    @home.finish_shopping!
    assert_refused(undo(removal), "item_archived")
    assert_equal "Leite novo", restored.reload.name
  end

  test "an old undo cannot undo a later sync removal of the same identity" do
    first = remove(@milk)
    submit(first, undo(first))
    restored = @home.list_items.find_by!(public_id: @milk.public_id)
    second = remove(restored)
    submit(second)
    assert_refused(undo(first), "removal_changed")
    assert_not ListItem.exists?(public_id: @milk.public_id)
    assert_equal "applied", submit(undo(second)).first[:status]
  end

  test "an old undo cannot undo a later PWA restoration and removal" do
    snapshot = @milk.attributes
    first = remove(@milk)
    submit(first)
    restored = @home.restore_item(snapshot)
    assert restored
    @home.remove_item(restored)
    assert_refused(undo(first), "removal_changed")
    assert_not ListItem.exists?(public_id: @milk.public_id)
  end

  test "pending duplicates conflict without losing the tombstone and can be retried" do
    removal = remove(@milk)
    submit(removal)
    other = @home.add_item("leite").item
    command = undo(removal)
    assert_refused(command, "duplicate_pending")
    assert SyncTombstone.exists?(public_id: @milk.public_id)
    @home.remove_item(other)
    assert_equal "applied", submit(command).first[:status]
  end

  test "missing foreign and other lineage dependencies disclose no receipt" do
    removal = remove(@milk)
    submit(removal)
    other_device, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    foreign_home = Household.create!(name: "Praia")
    foreign_device, = AndroidDevice.issue(household: foreign_home, name: "Praia", scope: AndroidDevice::SYNC)
    [ other_device, foreign_device ].each do |device|
      result = SyncCommandProcessor.new(device: device).call([ undo(removal) ]).first
      assert_equal "blocked", result[:status]
      assert_equal "dependency_not_applied", result[:error]
      assert_nil result[:data]
    end
    assert_equal "blocked", submit(undo({ "id" => uuid })).first[:status]
    assert SyncTombstone.exists?(public_id: @milk.public_id)
  end

  test "requires a successful removal dependency and ignores forged attributes" do
    missing = undo({ "id" => nil })
    assert_equal "malformed_command", submit(missing).first[:error]
    add = add_items_command([ uuid, "Farinha" ])
    submit(add)
    assert_equal "invalid_removal", submit(undo(add)).first[:error]
    removal = remove(@milk)
    submit(removal)
    command = undo(removal).merge("payload" => { "name" => "Forjado", "public_id" => uuid, "lock_version" => 500 })
    assert_equal "applied", submit(command).first[:status]
    assert_equal "Leite", @home.list_items.find_by!(public_id: @milk.public_id).name
  end

  test "a legacy absent category stays absent instead of being recategorized" do
    @milk.update_columns(category_id: nil)
    removal = remove(@milk)
    submit(removal)
    assert_equal "applied", submit(undo(removal)).first[:status]
    assert_nil @home.list_items.find_by!(public_id: @milk.public_id).category_id
  end

  test "reauthorized credential of the original lineage can compensate" do
    removal = remove(@milk)
    submit(removal)
    @device.revoke!
    @device, = AndroidDevice.issue(household: @home, name: "Ana outra vez", scope: AndroidDevice::SYNC,
      predecessor: @device)
    assert_equal "applied", submit(undo(removal)).first[:status]
  end

  private

  def remove(item, public_id: item.public_id)
    { "id" => uuid, "type" => "remove_item", "payload" => { "public_id" => public_id },
      "preconditions" => { "lock_version" => item.lock_version } }
  end

  def undo(removal)
    { "id" => uuid, "type" => "undo_remove", "depends_on" => removal["id"], "payload" => {} }
  end

  def submit(*commands) = SyncCommandProcessor.new(device: @device).call(commands)

  def assert_refused(command, reason)
    revision = @home.reload.sync_revision
    result = submit(command).first
    assert_equal "conflict", result[:status]
    assert_equal reason, result.dig(:data, "reason")
    assert_equal revision, @home.reload.sync_revision
    assert_not SyncCommand.exists?(command_id: command["id"])
  end
end
