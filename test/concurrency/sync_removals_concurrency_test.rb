require "test_helper"
require "support/barrier"

class SyncRemovalsConcurrencyTest < ActiveSupport::TestCase
  include ActionCable::TestHelper
  self.use_transactional_tests = false

  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @home = Household.create!(name: "Desfazer #{SecureRandom.hex(4)}")
    @device, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @item = @home.list_items.create!(name: "Tapioca")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @old_digest
    SyncCommand.where(household: @home).delete_all
    SyncAlias.where(household: @home).delete_all
    SyncTombstone.where(household: @home).delete_all
    ListItem.where(household: @home).delete_all
    AndroidDevice.where(household: @home).delete_all
    @home.delete
  end

  test "concurrent removals from one version have exactly one effect" do
    commands = 2.times.map { remove }
    results = race(commands)
    assert_equal %w[applied rejected], results.map { |result| result[:status] }.sort
    assert_equal "gone", results.find { |result| result[:status] == "rejected" }[:error]
    assert_equal 1, @home.reload.sync_revision
    assert_equal 1, SyncTombstone.where(household: @home).count
    assert_equal 1, SyncCommand.where(household: @home).count
  end

  test "concurrent identical undo submissions restore once and replay the same receipt" do
    removal = remove
    submit(removal)
    command = undo(removal)
    results = race([ command, command ])
    assert_equal %w[applied duplicate], results.map { |result| result[:status] }.sort
    assert_equal results.first[:data], results.last[:data]
    assert_equal 1, @home.list_items.count
    assert_equal 1, @home.list_items.first.lock_version
    assert_equal 2, @home.reload.sync_revision
    assert_equal 0, SyncTombstone.where(household: @home).count
  end

  test "different undo ids for the same removal cannot restore twice" do
    removal = remove
    submit(removal)
    results = race([ undo(removal), undo(removal) ])
    assert_equal %w[applied conflict], results.map { |result| result[:status] }.sort
    assert_equal 1, @home.list_items.count
    assert_equal 2, @home.reload.sync_revision
  end

  test "ledger insertion failure rolls back restoration tombstone revision and broadcasts" do
    removal = remove
    submit(removal)
    command = undo(removal)
    revision = @home.reload.sync_revision
    db = ActiveRecord::Base.connection
    db.execute(<<~SQL)
      CREATE TEMP TRIGGER fail_undo_remove_ledger BEFORE INSERT ON sync_commands
      WHEN NEW.command_id = '#{command["id"]}'
      BEGIN SELECT RAISE(ABORT, 'ledger unavailable'); END
    SQL
    streams = capture_turbo_stream_broadcasts(@home) do
      assert_broadcasts(SyncInvalidation.stream_for(@home), 0) do
        assert_raises(ActiveRecord::StatementInvalid) { submit(command) }
      end
    end
    assert_empty streams
    assert_equal revision, @home.reload.sync_revision
    assert_not ListItem.exists?(public_id: @item.public_id)
    assert_equal revision, SyncTombstone.find_by!(public_id: @item.public_id).revision
    assert_not SyncCommand.exists?(command_id: command["id"])
    db.execute("DROP TRIGGER fail_undo_remove_ledger")
    assert_list_broadcast(@home) { assert_equal "applied", submit(command)[:status] }
    assert_equal revision + 1, @home.reload.sync_revision
  ensure
    db&.execute("DROP TRIGGER IF EXISTS fail_undo_remove_ledger")
  end

  private

  def remove
    { "id" => SecureRandom.uuid, "type" => "remove_item", "payload" => { "public_id" => @item.public_id },
      "preconditions" => { "lock_version" => @item.lock_version } }
  end

  def undo(removal)
    { "id" => SecureRandom.uuid, "type" => "undo_remove", "depends_on" => removal["id"] }
  end

  def submit(command, device: @device) = SyncCommandProcessor.new(device: device).call([ command ]).first

  def race(commands)
    barrier = Barrier.new(commands.size)
    commands.map do |command|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          submit(command, device: AndroidDevice.find(@device.id))
        end
      end
    end.map(&:value)
  end
end
