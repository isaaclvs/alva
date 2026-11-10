require "test_helper"
require "support/barrier"

# Real transactions on independent SQLite connections. The test-harness
# transaction would hide both the rollback and the lost-update race.
class SyncRevisionConcurrencyTest < ActiveSupport::TestCase
  self.use_transactional_tests = false

  test "a rollback leaves no revision, data or tombstone behind" do
    household = Household.create!(name: "Revisão revertida")
    item = household.list_items.create!(name: "Arroz")
    before = household.reload.sync_revision

    assert_raises(RuntimeError) do
      household.with_list_lock do
        item.destroy!
        household.record_shared_change!(tombstones: [ item ])
        raise "boom"
      end
    end

    assert_equal before, household.reload.sync_revision
    assert ListItem.exists?(item.id)
    assert_equal 0, SyncTombstone.where(public_id: item.public_id).count
  ensure
    cleanup(household)
  end

  test "two confirmed changes on independent connections keep both increments" do
    household = Household.create!(name: "Revisões concorrentes")
    barrier = Barrier.new(2)

    results = 2.times.map do |index|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          Household.find(household.id).add_item(index.zero? ? "Arroz" : "Feijão")
        end
      end
    end.map(&:value)

    assert_equal [ :created, :created ], results.map(&:outcome)
    assert_equal 2, household.reload.sync_revision
    assert_equal 2, household.list_items.on_list.count
  ensure
    cleanup(household)
  end

  private

  def cleanup(household)
    return unless household

    SyncTombstone.where(household_id: household.id).delete_all
    ListItem.where(household_id: household.id).delete_all
    ShoppingTrip.where(household_id: household.id).delete_all
    HabitualItem.where(household_id: household.id).delete_all
    household.delete
  end
end
