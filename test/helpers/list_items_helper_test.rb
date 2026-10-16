require "test_helper"

class ListItemsHelperTest < ActionView::TestCase
  test "summary shows pending and purchased counts" do
    assert_equal "6 faltando · 2 no carrinho", list_summary_text(6, 2)
  end

  test "summary drops counts that are zero" do
    assert_equal "3 faltando", list_summary_text(3, 0)
    assert_equal "2 no carrinho", list_summary_text(0, 2)
  end

  test "summary for an empty list" do
    assert_equal "Nada faltando", list_summary_text(0, 0)
  end

  test "category icon uses the category's emoji" do
    assert_dom_equal %(<span class="grid size-7 place-items-center rounded-lg text-sm bg-emerald-500/15" aria-hidden="true">🥬</span>),
      category_icon(categories(:produce))
  end

  test "category icon falls back for uncategorized items" do
    assert_includes category_icon(nil), "📦"
  end
end
