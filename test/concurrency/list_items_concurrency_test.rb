require "test_helper"
require "timeout"

class ListItemsConcurrencyTest < ActiveSupport::TestCase
  self.use_transactional_tests = false

  class Barrier
    def initialize(parties)
      @parties = parties
      @arrived = 0
      @mutex = Mutex.new
      @condition = ConditionVariable.new
    end

    def wait
      @mutex.synchronize do
        @arrived += 1
        if @arrived == @parties
          @condition.broadcast
        else
          Timeout.timeout(5) do
            @condition.wait(@mutex) while @arrived < @parties
          end
        end
      end
    end
  end

  test "simultaneous normalized-name additions leave one pending item" do
    household = Household.create!(name: "Concorrência")
    barrier = Barrier.new(2)

    results = 2.times.map do |index|
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          Household.find(household.id).add_item(index.zero? ? "Leite" : "LÉITE")
        end
      end
    end.map(&:value)

    assert_equal 1, household.list_items.on_list.pending.count
    assert_equal [ :created, :duplicate ], results.map(&:outcome).sort_by(&:to_s)
  ensure
    if household
      ListItem.where(household_id: household.id).delete_all
      ShoppingTrip.where(household_id: household.id).delete_all
      household.delete
    end
  end

  test "independent SQLite connections cannot insert the same pending key after both reads pass" do
    household = Household.create!(name: "Índice concorrente")
    barrier = Barrier.new(2)
    observed = Queue.new
    timestamp = Time.current

    writes = 2.times.map do |index|
      Thread.new do
        ActiveRecord::Base.connection_pool.with_connection do |connection|
          observed << [ connection.object_id,
            connection.select_value("SELECT COUNT(*) FROM list_items WHERE household_id = #{household.id} AND normalized_name = 'banana' AND status = 0 AND shopping_trip_id IS NULL").to_i ]
          barrier.wait
          ListItem.insert!({ name: index.zero? ? "Banana" : "BANANA", normalized_name: "banana",
            household_id: household.id, status: 0, created_at: timestamp, updated_at: timestamp })
          :inserted
        rescue ActiveRecord::RecordNotUnique
          :duplicate_key
        end
      end
    end.map(&:value)

    observations = 2.times.map { observed.pop }
    assert_equal 2, observations.map(&:first).uniq.size
    assert_equal [ 0, 0 ], observations.map(&:last)
    assert_equal [ :duplicate_key, :inserted ], writes.sort_by(&:to_s)
    assert_equal 1, household.list_items.on_list.pending.where(normalized_name: "banana").count
  ensure
    if household
      ListItem.where(household_id: household.id).delete_all
      ShoppingTrip.where(household_id: household.id).delete_all
      household.delete
    end
  end

  test "simultaneous finish requests create one trip and archive only purchased items" do
    household = Household.create!(name: "Compra concorrente")
    purchased = household.list_items.create!(name: "Arroz", status: :purchased, purchased_at: Time.current)
    pending = household.list_items.create!(name: "Feijão")
    barrier = Barrier.new(2)

    trips = 2.times.map do
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          Household.find(household.id).finish_shopping!
        end
      end
    end.map(&:value)

    assert_equal 1, trips.compact.size
    assert_equal 1, household.shopping_trips.count
    assert_equal trips.compact.first, purchased.reload.shopping_trip
    assert_nil pending.reload.shopping_trip
    assert_equal [ pending ], household.list_items.on_list.to_a
  ensure
    if household
      ListItem.where(household_id: household.id).delete_all
      ShoppingTrip.where(household_id: household.id).delete_all
      household.delete
    end
  end

  test "a delayed object cannot mutate an item after it is archived" do
    household = Household.create!(name: "Ação atrasada")
    item = household.list_items.create!(name: "Arroz", status: :purchased, purchased_at: Time.current)
    delayed_copy = ListItem.find(item.id)

    household.finish_shopping!

    assert_raises(ListItem::StaleAction) { delayed_copy.toggle_purchased! }
    assert delayed_copy.reload.purchased?
    assert_not_nil delayed_copy.shopping_trip_id
  ensure
    if household
      ListItem.where(household_id: household.id).delete_all
      ShoppingTrip.where(household_id: household.id).delete_all
      household.delete
    end
  end

  test "simultaneous undo requests restore a removed row once" do
    household = Household.create!(name: "Desfazer concorrente")
    item = household.list_items.create!(name: "Arroz")
    snapshot = ListItemSnapshot.load(ListItemSnapshot.dump(item))
    item.destroy!
    barrier = Barrier.new(2)

    restored = 2.times.map do
      Thread.new do
        barrier.wait
        ActiveRecord::Base.connection_pool.with_connection do
          Household.find(household.id).restore_item(snapshot)
        end
      end
    end.map(&:value)

    assert_equal 1, restored.compact.size
    assert_equal 1, household.list_items.on_list.pending.where(name: "Arroz").count
  ensure
    if household
      ListItem.where(household_id: household.id).delete_all
      ShoppingTrip.where(household_id: household.id).delete_all
      household.delete
    end
  end
end
