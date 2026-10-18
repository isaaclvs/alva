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

  def result(outcome, name = "Item")
    Household::AddResult.new(ListItem.new(name: name), outcome)
  end

  test "add notice counts the added items" do
    assert_equal "3 itens adicionados", add_items_notice([ result(:created), result(:created), result(:revived) ])
  end

  test "add notice uses the singular for one added item" do
    assert_equal "1 item adicionado · Leite já estava na lista",
      add_items_notice([ result(:created), result(:duplicate, "Leite") ])
  end

  test "add notice names the items already on the list" do
    assert_equal "2 itens adicionados · Leite já estava na lista",
      add_items_notice([ result(:created), result(:created), result(:duplicate, "Leite") ])
    assert_equal "1 item adicionado · Leite e Pão já estavam na lista",
      add_items_notice([ result(:revived), result(:duplicate, "Leite"), result(:duplicate, "Pão") ])
    assert_equal "1 item adicionado · Leite, Pão e Ovos já estavam na lista",
      add_items_notice([ result(:created), result(:duplicate, "Leite"), result(:duplicate, "Pão"), result(:duplicate, "Ovos") ])
  end

  test "add notice when nothing was added" do
    assert_equal "Nada adicionado · Leite e Pão já estavam na lista",
      add_items_notice([ result(:duplicate, "Leite"), result(:duplicate, "Pão") ])
  end
end
