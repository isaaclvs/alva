require "test_helper"

class ItemNameSplitterTest < ActiveSupport::TestCase
  test "keeps a single name as is" do
    assert_equal [ "Leite" ], ItemNameSplitter.call("Leite")
    assert_equal [ "Leite integral" ], ItemNameSplitter.call("Leite integral")
  end

  test "splits on commas, semicolons and newlines" do
    assert_equal %w[leite pão ovos arroz], ItemNameSplitter.call("leite, pão; ovos\narroz")
  end

  test "strips each part and ignores blank ones" do
    assert_equal %w[leite ovos], ItemNameSplitter.call("  leite ,, ; \n ovos  ,")
  end

  test "nothing usable gives no names" do
    assert_equal [], ItemNameSplitter.call(" , ;\n")
    assert_equal [], ItemNameSplitter.call("")
    assert_equal [], ItemNameSplitter.call(nil)
  end

  test "drops repeats within the text, keeping the first spelling" do
    assert_equal [ "Leite", "Pão" ], ItemNameSplitter.call("Leite, leite, LEITES, Pão, pães, pao")
  end

  test "keeps at most 30 names" do
    names = ItemNameSplitter.call((1..40).map { |n| "item #{n}" }.join(", "))

    assert_equal 30, names.size
    assert_equal "item 1", names.first
    assert_equal "item 30", names.last
  end

  test "repeats don't count against the cap" do
    text = ([ "leite" ] * 20 + (1..30).map { |n| "item #{n}" }).join(";")

    assert_equal 30, ItemNameSplitter.call(text).size
  end
end
