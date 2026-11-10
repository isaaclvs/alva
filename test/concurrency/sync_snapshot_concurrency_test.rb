require "test_helper"
require "rack/mock"

# Real transactions on independent SQLite connections, like the other
# concurrency tests: the harness transaction of a normal test would hide
# exactly what is being checked here, a commit landing in the middle of a read.
class SyncSnapshotConcurrencyTest < ActiveSupport::TestCase
  self.use_transactional_tests = false

  # [table, n]: the reader is stopped right after the n-th query that reads
  # `table`, so every collection takes a turn as the one that "already read".
  READ_POINTS = [
    [ "households", 1 ], [ "categories", 1 ], [ "shopping_trips", 1 ], [ "sync_scopes", 1 ], [ "catalog_items", 1 ],
    [ "list_items", 1 ], [ "list_items", 2 ], [ "habitual_items", 1 ], [ "sync_tombstones", 1 ]
  ].freeze

  setup do
    @previous_digest = ENV["ALVA_PASSWORD_DIGEST"]
    ENV["ALVA_PASSWORD_DIGEST"] = BCrypt::Password.create("senha-da-casa", cost: 4).to_s
    @home = Household.create!(name: "Leitura concorrente")
  end

  teardown do
    ENV["ALVA_PASSWORD_DIGEST"] = @previous_digest
    cleanup(@home)
  end

  READ_POINTS.each do |table, nth|
    test "a commit after the #{nth.ordinalize} read of #{table} never reaches the document" do
      seed
      baseline = SyncSnapshot.call(@home)

      resumed = Queue.new
      stopped = Queue.new
      reads = 0
      subscriber = ActiveSupport::Notifications.subscribe("sql.active_record") do |*, payload|
        next unless Thread.current[:snapshot_reader] && payload[:sql].include?(%(FROM "#{table}"))
        next unless (reads += 1) == nth

        stopped << true
        resumed.pop(timeout: 5) || raise("reader was never resumed")
      end

      reader = Thread.new do
        Thread.current[:snapshot_reader] = true
        ActiveRecord::Base.connection_pool.with_connection { SyncSnapshot.call(@home) }
      end

      begin
        assert stopped.pop(timeout: 5), "the reader never reached #{table}"
        # The reader is halfway through; writers are not blocked by it.
        commit_everywhere
      ensure
        resumed << true
        ActiveSupport::Notifications.unsubscribe(subscriber)
      end

      document = reader.value
      assert_equal baseline, document
      assert_operator SyncSnapshot.call(@home)[:revision], :>, document[:revision]
    end
  end

  test "documents read while another device writes are never torn" do
    _, token = AndroidDevice.issue(household: @home, name: "Leitor", scope: AndroidDevice::SYNC)
    app = Rack::MockRequest.new(Rails.application)
    cycles = 15
    steps = cycles * 3
    writing = true
    generation = 0
    observed = Queue.new

    # Two readers poll nonstop. After every committed change the writer waits
    # for a document requested after that commit, so each of the revisions is
    # read at least once, while the readers keep reading during the next write.
    readers = 2.times.map do
      Thread.new do
        documents = []
        while writing || documents.size < 3
          before = generation
          response = app.get("/api/v1/snapshot", "HTTP_AUTHORIZATION" => "Bearer #{token}")
          raise "unexpected #{response.status}" unless response.status == 200

          documents << JSON.parse(response.body)
          observed << before
        end
        documents
      end
    end

    writer = Thread.new do
      published = lambda do
        generation += 1
        loop { break if (observed.pop(timeout: 5) || raise("no reader saw revision #{generation}")) >= generation }
      end
      observed.pop(timeout: 5) || raise("a reader never started")
      ActiveRecord::Base.connection_pool.with_connection do
        home = Household.find(@home.id)
        cycles.times do
          item = home.add_item("Leite").item
          published.call
          item.toggle_purchased!
          published.call
          home.finish_shopping!
          published.call
        end
      end
    ensure
      writing = false
    end

    writer.join
    documents = readers.flat_map(&:value)

    # Each cycle is add, toggle, finish: three revisions, so the revision alone
    # says how many trips, archived items and live items a consistent document
    # must contain.
    documents.each do |document|
      trips, step = document["revision"].divmod(3)
      assert_equal trips, document["shopping_trips"].size, "revision #{document["revision"]}"
      assert_equal trips, document["archived_items"].size, "revision #{document["revision"]}"
      assert_equal [ [], [ "pending" ], [ "purchased" ] ][step], document["list_items"].map { _1["status"] },
        "revision #{document["revision"]}"
    end
    assert_equal (0..steps).to_a, documents.map { _1["revision"] }.uniq.sort
    final = SyncSnapshot.call(@home)
    assert_equal [ steps, cycles ], [ final[:revision], final[:shopping_trips].size ]
  end

  private

  def seed
    @home.add_item("Arroz")
    @home.add_item("Feijão").item.toggle_purchased!
    @home.add_item("Sal")
    @home.add_item("Café")
    @home.add_habitual_item("Alho")
  end

  # One change to every collection the snapshot reads, on the test's own
  # connection while the reader holds its own.
  def commit_everywhere
    home = Household.find(@home.id)
    home.finish_shopping!
    home.remove_item(home.list_items.on_list.find_by!(name: "Sal"))
    home.add_habitual_item("Habitual novo")
    home.add_item("Novo")
    home.list_items.on_list.find_by!(name: "Café").correct(name: "Café", category_id: categories(:dairy).id)
  end

  def cleanup(household)
    return unless household

    SyncTombstone.where(household_id: household.id).delete_all
    AndroidDevice.where(household_id: household.id).delete_all
    ListItem.where(household_id: household.id).delete_all
    ShoppingTrip.where(household_id: household.id).delete_all
    HabitualItem.where(household_id: household.id).delete_all
    CatalogItem.where(learned: true).delete_all
    SyncScope.delete_all
    household.delete
  end
end
