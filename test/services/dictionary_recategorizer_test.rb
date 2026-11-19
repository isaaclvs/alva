require "test_helper"
require "rake"

class DictionaryRecategorizerTest < ActiveSupport::TestCase
  setup do
    @household = households(:home)
    @other = categories(:other)
    @produce = categories(:produce)
  end

  test "moves a fallback item to the category the dictionary now knows" do
    item = @household.list_items.create!(name: "Banana", category: @other)
    CatalogItem.find_by!(normalized_name: "banana").update!(category: @produce)

    assert_equal 1, DictionaryRecategorizer.call(@household)
    assert_equal @produce, item.reload.category
  end

  test "leaves a category picked by hand alone" do
    item = @household.list_items.create!(name: "Banana", category: @other)
    ItemCategorizer.learn("Banana", @other)

    assert_equal 0, DictionaryRecategorizer.call(@household)
    assert_equal @other, item.reload.category
  end

  test "leaves a name with no dictionary match in the fallback" do
    item = @household.list_items.create!(name: "Coisa rara", category: @other)

    assert_equal 0, DictionaryRecategorizer.call(@household)
    assert_equal @other, item.reload.category
  end

  test "ignores items already outside the fallback, uncategorized items and history" do
    kept = @household.list_items.create!(name: "Banana", category: @produce)
    uncategorized = @household.list_items.create!(name: "Maçã", category: @produce)
    uncategorized.update_columns(category_id: nil)
    trip = @household.shopping_trips.create!(finished_at: Time.current)
    archived = @household.list_items.create!(name: "Uva", category: @other, status: :purchased, shopping_trip: trip)

    assert_equal 0, DictionaryRecategorizer.call(@household)
    assert_equal @produce, kept.reload.category
    assert_nil uncategorized.reload.category
    assert_equal @other, archived.reload.category
  end

  test "publishes one shared change for the household" do
    @household.list_items.create!(name: "Banana", category: @other)
    CatalogItem.find_by!(normalized_name: "banana").update!(category: @produce)

    assert_difference -> { @household.reload.sync_revision }, 1 do
      DictionaryRecategorizer.call(@household)
    end
  end

  test "moves nothing and bumps no revision a second time" do
    @household.list_items.create!(name: "Banana", category: @other)
    CatalogItem.find_by!(normalized_name: "banana").update!(category: @produce)
    DictionaryRecategorizer.call(@household)

    assert_no_difference -> { @household.reload.sync_revision } do
      assert_equal 0, DictionaryRecategorizer.call(@household)
    end
  end

  test "the task does the same for every household" do
    Rails.application.load_tasks unless Rake::Task.task_defined?("dictionary:recategorize")
    another = Household.create!(name: "Outra casa")
    [ @household, another ].each { |household| household.list_items.create!(name: "Banana", category: @other) }
    CatalogItem.find_by!(normalized_name: "banana").update!(category: @produce)

    assert_output(/2 items recategorized/) { Rake::Task["dictionary:recategorize"].invoke }
    assert_equal [ @produce ], [ @household, another ].map { |household| household.list_items.find_by!(name: "Banana").category }.uniq
  ensure
    Rake::Task["dictionary:recategorize"].reenable
  end

  test "the task says one item was recategorized in the singular" do
    Rails.application.load_tasks unless Rake::Task.task_defined?("dictionary:recategorize")
    @household.list_items.create!(name: "Banana", category: @other)
    CatalogItem.find_by!(normalized_name: "banana").update!(category: @produce)

    assert_output("1 item recategorized\n") { Rake::Task["dictionary:recategorize"].invoke }
  ensure
    Rake::Task["dictionary:recategorize"].reenable
  end
end
