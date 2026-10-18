require "test_helper"

class ListPdf::ColumnLayoutTest < ActiveSupport::TestCase
  # A card is 10 high plus 10 per item; cards in a column are 5 apart.
  def layout(groups, first_capacity: 100, capacity: 100)
    ListPdf::ColumnLayout.call(groups, height_for: ->(rows) { 10 + rows * 10 },
      first_capacity: first_capacity, capacity: capacity, gap: 5)
  end

  def labels(column)
    column.map(&:label)
  end

  test "balances a list that fits on one page, keeping reading order" do
    pages = layout([ [ "A", %w[a1 a2] ], [ "B", %w[b1] ], [ "C", %w[c1 c2 c3] ] ])

    assert_equal 1, pages.size
    assert_equal [ %w[A B], %w[C] ], pages.first.map { |column| labels(column) }
    assert pages.flatten.none?(&:continued)
  end

  test "splits a card that doesn't fit and continues it in the next column" do
    pages = layout([ [ "A", (1..10).map { |i| "a#{i}" } ], [ "B", %w[b1 b2 b3] ] ], first_capacity: 60)

    left, right = pages.first
    assert_equal [ "A" ], labels(left)
    assert_equal %w[a1 a2 a3 a4 a5], left.first.items
    assert_not left.first.continued
    assert_equal %w[a6 a7 a8 a9 a10], right.first.items
    assert right.first.continued
    assert_equal [ 10, 10 ], [ left.first.total, right.first.total ]

    assert_equal 2, pages.size
    assert_equal [ "B" ], labels(pages.last.first)
  end

  test "moves a card to the next column instead of leaving one item under its heading" do
    # Too long to balance on one page. After A the left column only has room
    # for B's heading and one of its three items.
    groups = [ [ "A", (1..6).map { |i| "a#{i}" } ], [ "B", %w[b1 b2 b3] ], [ "C", (1..8).map { |i| "c#{i}" } ] ]
    pages = layout(groups, first_capacity: 95, capacity: 95)

    left, right = pages.first
    assert_equal [ "A" ], labels(left)
    assert_equal %w[B C], labels(right)
    assert_equal %w[b1 b2 b3], right.first.items
    assert_equal %w[c1 c2 c3 c4], right.last.items
    assert pages.second.first.first.continued
  end

  test "always places something in an empty column, even an oversized card" do
    pages = layout([ [ "A", %w[a1 a2] ] ], first_capacity: 5, capacity: 5)

    assert_equal [ %w[a1], %w[a2] ], pages.first.map { |column| column.first.items }
  end

  test "an empty list is one empty page" do
    assert_equal [ [ [], [] ] ], layout([])
  end
end
