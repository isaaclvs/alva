require "test_helper"
require "support/barrier"
require "support/sync_command_builders"

class SyncCorrectionsConcurrencyTest < ActiveSupport::TestCase
  include SyncCommandBuilders
  self.use_transactional_tests = false

  setup do
    @old_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("teste", cost: 4).to_s
    @homes = 2.times.map { Household.create!(name: "Correção #{SecureRandom.hex(4)}") }
    @devices = @homes.map { |home| AndroidDevice.issue(household: home, name: "Native", scope: AndroidDevice::SYNC).first }
    @items = @homes.map { |home| home.list_items.create!(name: "Produto #{SecureRandom.hex(8)}") }
    @revision = SyncScope.revision_for(SyncScope::DICTIONARY)
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @old_digest
    CatalogItem.where(normalized_name: @items.map(&:normalized_name)).delete_all
    SyncScope.where(name: SyncScope::DICTIONARY).update_all(revision: @revision)
    @homes.each do |home|
      SyncCommand.where(household: home).delete_all
      ListItem.where(household: home).delete_all
      AndroidDevice.where(household: home).delete_all
      home.delete
    end
  end

  test "corrections in different households serialize global learning without artificial revisions" do
    commands = @items.map do |item|
      correct_item_command(item.public_id, item.name, lock_version: 0,
        category_id: categories(:produce).public_id, dictionary_revision: @revision)
    end
    scopes_before = SyncScope.pluck(:name).sort
    results = race(@devices.zip(commands))
    assert_equal [ "applied", "applied" ], results.map { |result| result[:status] }
    assert_equal 1, results.count { |result| result[:data]["learned"] }
    assert_equal 1, results.count { |result| result[:data]["learning_pending"] }, results.inspect
    assert_equal @revision + 1, SyncScope.uncached { SyncScope.revision_for(SyncScope::DICTIONARY) }
    assert_equal (scopes_before + [ SyncScope::DICTIONARY ]).uniq.sort, SyncScope.uncached { SyncScope.pluck(:name).sort }
    @items.each { |item| assert_equal categories(:produce).id, item.reload.category_id }
  end

  test "two corrections from the same observed item version cannot overwrite one another" do
    item = @items.first
    commands = [ "Nome A", "Nome B" ].map { |name| correct_item_command(item.public_id, name, lock_version: 0) }
    results = race([ [ @devices.first, commands.first ], [ @devices.first, commands.last ] ])
    assert_equal %w[applied conflict], results.map { |result| result[:status] }.sort
    assert_equal 1, item.reload.lock_version
    assert_includes [ "Nome A", "Nome B" ], item.name
  end

  test "ledger write failure rolls back the correction dictionary revision and broadcast" do
    item = @items.first
    command = correct_item_command(item.public_id, item.name, lock_version: 0,
      category_id: categories(:produce).public_id, dictionary_revision: @revision)
    before_category = item.category_id
    before_revision = @homes.first.reload.sync_revision
    db = ActiveRecord::Base.connection
    db.execute(<<~SQL)
      CREATE TEMP TRIGGER fail_correction_ledger BEFORE INSERT ON sync_commands
      WHEN NEW.command_id = '#{command["id"]}'
      BEGIN SELECT RAISE(ABORT, 'ledger unavailable'); END
    SQL
    streams = capture_turbo_stream_broadcasts(@homes.first) do
      assert_raises(ActiveRecord::StatementInvalid) do
        SyncCommandProcessor.new(device: @devices.first).call([ command ])
      end
    end
    assert_empty streams
    assert_equal before_category, item.reload.category_id
    assert_equal 0, item.lock_version
    assert_equal before_revision, @homes.first.reload.sync_revision
    assert_equal @revision, SyncScope.revision_for(SyncScope::DICTIONARY)
    assert_not CatalogItem.exists?(normalized_name: item.normalized_name)
    assert_not SyncCommand.exists?(command_id: command["id"])
  ensure
    db&.execute("DROP TRIGGER IF EXISTS fail_correction_ledger")
  end

  private

  def race(pairs)
    barrier = Barrier.new(pairs.size)
    pairs.map do |device, command|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          SyncCommandProcessor.new(device: AndroidDevice.find(device.id)).call([ command ]).first
        end
      end
    end.map(&:value)
  end
end
