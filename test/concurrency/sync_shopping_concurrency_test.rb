require "test_helper"
require "support/barrier"
require "support/sync_command_builders"

# Real transactions on independent SQLite connections, with the real executors.
class SyncShoppingConcurrencyTest < ActiveSupport::TestCase
  include SyncCommandBuilders

  self.use_transactional_tests = false

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = Household.create!(name: "Compras concorrentes #{SecureRandom.hex(4)}")
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @isaac, = AndroidDevice.issue(household: @home, name: "Isaac", scope: AndroidDevice::SYNC)
    @milk = purchase("Leite")
    @bread = purchase("Pão")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    SyncCommand.where(household_id: @home.id).delete_all
    SyncAlias.where(household_id: @home.id).delete_all
    SyncTombstone.where(household_id: @home.id).delete_all
    ListItem.where(household_id: @home.id).delete_all
    ShoppingTrip.where(household_id: @home.id).delete_all
    AndroidDevice.where(household_id: @home.id).delete_all
    @home.delete
  end

  test "two phones finishing the same items at once make one trip; the loser finds them archived" do
    items = observed(@milk, @bread)
    before = @home.reload.sync_revision

    results = race(@ana => finish_shopping_command(*items), @isaac => finish_shopping_command(*items))

    assert_equal %w[applied rejected], results.map { |result| result[:status] }.sort
    assert_equal "gone", results.find { |result| result[:status] == "rejected" }[:error]
    assert_equal 1, @home.shopping_trips.count
    assert_equal [ @home.shopping_trips.first.id ], @home.list_items.archived.pluck(:shopping_trip_id).uniq
    assert_equal before + 1, @home.reload.sync_revision
  end

  test "the same finish sent twice at once applies once and answers duplicate to the other" do
    command = finish_shopping_command(*observed(@milk))

    results = race([ [ @ana, command ], [ @ana, command ] ]) # one credential, two connections

    assert_equal %w[applied duplicate], results.map { |result| result[:status] }.sort
    assert_equal 1, @home.shopping_trips.count
  end

  test "finishing while another phone unmarks never archives an unmarked item" do
    items = observed(@milk)

    results = race(@ana => finish_shopping_command(*items),
      @isaac => set_item_state_command(@milk.public_id, "pending", lock_version: items.first.last))

    finish, unmark = results
    # Whoever the lock serves first wins: the finish archives it and the unmark finds it gone,
    # or the unmark applies and the finish (now stale) conflicts. Never both.
    assert_includes [ %w[applied rejected], %w[conflict applied] ], [ finish[:status], unmark[:status] ]
    @milk.reload
    if finish[:status] == "applied"
      assert @milk.shopping_trip_id && @milk.purchased?
    else
      assert_nil @milk.shopping_trip_id
      assert @milk.pending?
      assert_equal 0, @home.shopping_trips.count
    end
  end

  test "two phones undoing the same trip at once restore it once" do
    trip_id = SyncCommandProcessor.new(device: AndroidDevice.find(@ana.id))
      .call([ finish_shopping_command(*observed(@milk, @bread)) ]).first[:data]["trip"]["public_id"]

    results = race(@ana => undo_shopping_command(trip_id), @isaac => undo_shopping_command(trip_id))

    assert_equal %w[applied rejected], results.map { |result| result[:status] }.sort
    assert_equal "gone", results.find { |result| result[:status] == "rejected" }[:error]
    assert_equal 0, @home.shopping_trips.count
    assert_equal 2, @home.list_items.on_list.count
  end

  test "undoing while another phone finishes a newer trip never undoes the older one" do
    older = SyncCommandProcessor.new(device: AndroidDevice.find(@ana.id))
      .call([ finish_shopping_command(*observed(@milk)) ]).first[:data]["trip"]["public_id"]

    results = race(@ana => undo_shopping_command(older), @isaac => finish_shopping_command(*observed(@bread)))

    undo, finish = results
    # Either the undo runs first (older trip is restored, then the finish makes a new one),
    # or the finish runs first and the older trip is no longer the latest.
    assert_includes [ %w[applied applied], %w[conflict applied] ], [ undo[:status], finish[:status] ]
    if undo[:status] == "conflict"
      assert_equal 2, @home.shopping_trips.count
      assert @milk.reload.shopping_trip_id
    else
      assert_equal 1, @home.shopping_trips.count
      assert_nil @milk.reload.shopping_trip_id
    end
  end

  private

  def purchase(name)
    @home.list_items.create!(name: name).tap { |item| item.toggle_purchased!(sync: false) }
  end

  def observed(*items) = items.map { |item| [ item.public_id, item.reload.lock_version ] }

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
