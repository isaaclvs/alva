require "test_helper"

class SeedsTest < ActiveSupport::TestCase
  setup do
    Rails.application.load_seed
  end

  test "creates the default household" do
    assert Household.exists?(name: "Casa")
  end

  test "is idempotent" do
    counts = -> { [ Household.count, Category.count, CatalogItem.count ] }
    before = counts.call
    Rails.application.load_seed
    assert_equal before, counts.call
  end
end
