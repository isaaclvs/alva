require "test_helper"
require "support/barrier"
require "support/sync_command_builders"

# Real transactions on independent SQLite connections, with the real executors.
class SyncItemCommandsConcurrencyTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  self.use_transactional_tests = false

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = Household.create!(name: "Itens concorrentes #{SecureRandom.hex(4)}")
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    SyncCommand.where(household_id: @home.id).delete_all
    SyncAlias.where(household_id: @home.id).delete_all
    SyncTombstone.where(household_id: @home.id).delete_all
    ListItem.where(household_id: @home.id).delete_all
    AndroidDevice.where(household_id: @home.id).delete_all
    @home.delete
  end

  test "two phones adding the same name at once leave one item, one alias and one revision" do
    ana_item, isaac_item = uuid, uuid

    results = race(@ana => add_items_command([ ana_item, "Tapioca" ]), @isaac => add_items_command([ isaac_item, "tapioca" ]))

    assert_equal %w[applied noop], results.map { |result| result[:status] }.sort
    assert_equal 1, @home.list_items.where(normalized_name: "tapioca").count
    canonical = @home.list_items.first.public_id
    assert_includes [ ana_item, isaac_item ], canonical
    loser = ([ ana_item, isaac_item ] - [ canonical ]).first
    assert_equal canonical, SyncAlias.find_by!(public_id: loser).canonical_public_id
    assert_equal 1, @home.reload.sync_revision
  end

  test "two phones asking for the same state at once change the item once" do
    item = @home.list_items.create!(name: "Tapioca")

    results = race(@ana => set_item_state_command(item.public_id, "purchased", lock_version: 0),
      @isaac => set_item_state_command(item.public_id, "purchased", lock_version: 0))

    assert_equal %w[applied noop], results.map { |result| result[:status] }.sort
    item.reload
    assert_equal [ "purchased", 1 ], [ item.status, item.lock_version ]
  end

  test "marking and unmarking at once from the same version never lets the stale one overwrite" do
    item = @home.list_items.create!(name: "Tapioca")

    results = race(@ana => set_item_state_command(item.public_id, "purchased", lock_version: 0),
      @isaac => set_item_state_command(item.public_id, "pending", lock_version: 0))

    # Whoever the lock serves first decides: either the mark applies and the
    # unmark (now stale) conflicts, or the unmark finds the item already
    # pending (a no-op) and the mark applies. It never ends unmarked by a
    # request that had not seen the mark.
    assert_equal 1, results.count { |result| result[:status] == "applied" }
    assert_includes [ %w[applied conflict], %w[applied noop] ], results.map { |result| result[:status] }.sort
    item.reload
    assert_equal [ "purchased", 1 ], [ item.status, item.lock_version ]
  end

  test "two phones undoing the same mark at once restore the item once" do
    item = @home.list_items.create!(name: "Tapioca")
    submit_one(@ana, set_item_state_command(item.public_id, "purchased", lock_version: 0))

    results = race(@ana => undo_item_state_command(item.public_id, "pending", lock_version: 1),
      @isaac => undo_item_state_command(item.public_id, "pending", lock_version: 1))

    assert_equal %w[applied noop], results.map { |result| result[:status] }.sort
    item.reload
    assert_equal [ "pending", nil, 2 ], [ item.status, item.purchased_at, item.lock_version ]
    assert_equal 2, @home.reload.sync_revision
  end

  test "an undo and an unmark from the same version at once change the item once" do
    item = @home.list_items.create!(name: "Tapioca")
    submit_one(@ana, set_item_state_command(item.public_id, "purchased", lock_version: 0))

    results = race(@ana => undo_item_state_command(item.public_id, "pending", lock_version: 1),
      @isaac => set_item_state_command(item.public_id, "pending", lock_version: 1))

    # Whoever the lock serves first applies; the other finds the wanted state (a noop).
    assert_equal %w[applied noop], results.map { |result| result[:status] }.sort
    assert_equal [ "pending", 2 ], item.reload.then { |reloaded| [ reloaded.status, reloaded.lock_version ] }
  end

  private

  def submit_one(device, command)
    SyncCommandProcessor.new(device: AndroidDevice.find(device.id)).call([ command ]).first
  end

  def race(commands_by_device)
    barrier = Barrier.new(commands_by_device.size)
    commands_by_device.map do |device, command|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          SyncCommandProcessor.new(device: AndroidDevice.find(device.id)).call([ command ]).first
        end
      end
    end.map(&:value)
  end
end
