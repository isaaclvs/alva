require "test_helper"
require "support/barrier"
require "support/sync_command_executors"

# Real transactions on independent SQLite connections: the test-harness
# transaction would hide both the lock and the rollback.
class SyncCommandConcurrencyTest < ActiveSupport::TestCase
  self.use_transactional_tests = false

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = Household.create!(name: "Concorrência #{SecureRandom.hex(4)}")
    @ana, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC)
    @executors = { "add_items" => SyncCommandExecutors::ADD_ITEMS }
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    SyncCommand.where(household_id: @home.id).delete_all
    SyncTombstone.where(household_id: @home.id).delete_all
    ListItem.where(household_id: @home.id).delete_all
    AndroidDevice.where(household_id: @home.id).delete_all
    @home.delete
  end

  test "the same command sent twice at once takes effect once" do
    id = SecureRandom.uuid

    results = race(2) { |device| send_command(device, id, "Tapioca") }

    assert_equal %w[applied duplicate], results.map { |r| r[:status] }.sort
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, @home.reload.sync_revision
    assert_equal 1, SyncCommand.where(command_id: id).count
    original = results.find { |r| r[:status] == "applied" }
    replay = results.find { |r| r[:status] == "duplicate" }
    assert_equal original[:data], replay[:data]
    assert_equal original[:revision], replay[:revision]
  end

  test "a retry racing from a re-authorized credential of the same phone still takes effect once" do
    id = SecureRandom.uuid
    @ana.revoke!
    reauthorized, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC, predecessor: @ana)
    devices = [ @ana, reauthorized ]
    barrier = Barrier.new(2)

    results = devices.map do |device|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection { send_command(AndroidDevice.find(device.id), id, "Tapioca") }
      end
    end.map(&:value)

    assert_equal %w[applied duplicate], results.map { |r| r[:status] }.sort
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "two contents racing under one id apply exactly one and reject the other" do
    id = SecureRandom.uuid
    barrier = Barrier.new(2)

    results = %w[Tapioca Polvilho].map do |name|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection { send_command(AndroidDevice.find(@ana.id), id, name) }
      end
    end.map(&:value)

    assert_equal %w[applied rejected], results.map { |r| r[:status] }.sort
    assert_equal [ "idempotency_conflict" ], results.filter_map { |r| r[:error] }
    assert_equal 1, @home.list_items.count
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "independent commands for the same name produce one item and two answers" do
    results = race(2) { |device| send_command(device, SecureRandom.uuid, "Tapioca") }

    assert_equal %w[applied noop], results.map { |r| r[:status] }.sort
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, @home.reload.sync_revision, "the no-op does not move the revision"
    assert_equal 2, SyncCommand.where(household_id: @home.id).count
  end

  test "a failure after the effect leaves nothing committed and the command can be sent again" do
    id = SecureRandom.uuid
    flaky = { "add_items" => SyncCommandExecutors.failing_then_adding(1) }

    assert_raises(RuntimeError) { send_command(AndroidDevice.find(@ana.id), id, "Tapioca", executors: flaky) }

    committed = in_other_connection do
      [ ListItem.where(household_id: @home.id).count, Household.find(@home.id).sync_revision,
        SyncCommand.where(command_id: id).count, SyncTombstone.where(household_id: @home.id).count ]
    end
    assert_equal [ 0, 0, 0, 0 ], committed

    result = send_command(AndroidDevice.find(@ana.id), id, "Tapioca", executors: flaky)
    assert_equal "applied", result[:status]
    assert_equal [ 1, 1, 1 ], [ @home.list_items.count, @home.reload.sync_revision, SyncCommand.where(command_id: id).count ]
  end

  test "when the first of two racing sends fails, the other one applies it" do
    id = SecureRandom.uuid
    flaky = { "add_items" => SyncCommandExecutors.failing_then_adding(1) }
    barrier = Barrier.new(2)

    outcomes = 2.times.map do
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          send_command(AndroidDevice.find(@ana.id), id, "Tapioca", executors: flaky)
        rescue RuntimeError => error
          error.message
        end
      end
    end.map(&:value)

    assert_equal [ "falha depois do efeito" ], outcomes.grep(String)
    assert_equal [ "applied" ], outcomes.grep(Hash).map { |r| r[:status] }
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, SyncCommand.where(command_id: id).count
  end

  test "a response lost after the commit is answered from the ledger, even through a new credential" do
    id = SecureRandom.uuid
    first = send_command(AndroidDevice.find(@ana.id), id, "Tapioca")
    # The client never saw `first`: its token is revoked and the phone pairs again.
    @ana.revoke!
    reauthorized, = AndroidDevice.issue(household: @home, name: "Ana", scope: AndroidDevice::SYNC, predecessor: @ana)

    replay = in_other_connection { send_command(AndroidDevice.find(reauthorized.id), id, "Tapioca") }

    assert_equal "duplicate", replay[:status]
    assert_equal first[:data], replay[:data]
    assert_equal 1, @home.list_items.where(name: "Tapioca").count
    assert_equal 1, @home.reload.sync_revision
    assert_equal @ana.id, SyncCommand.find_by!(command_id: id).android_device_id
  end

  private

  def race(parties, &block)
    barrier = Barrier.new(parties)
    parties.times.map do
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection { block.call(AndroidDevice.find(@ana.id)) }
      end
    end.map(&:value)
  end

  def in_other_connection(&block)
    Thread.new { ActiveRecord::Base.connection_pool.with_connection(&block) }.value
  end

  def send_command(device, id, name, executors: @executors)
    command = { "id" => id, "type" => "add_items", "payload" => { "names" => [ name ] } }
    SyncCommandProcessor.new(device:, executors:).call([ command ]).first
  end
end
