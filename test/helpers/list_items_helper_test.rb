require "test_helper"

class ListItemsHelperTest < ActionView::TestCase
  test "summary shows pending and purchased counts" do
    assert_equal "6 pendentes · 2 comprados", list_summary_text(6, 2)
  end

  test "summary uses the singular and only mentions purchased items once there are some" do
    assert_equal "1 pendente · 1 comprado", list_summary_text(1, 1)
    assert_equal "3 pendentes", list_summary_text(3, 0)
    assert_equal "0 pendentes · 2 comprados", list_summary_text(0, 2)
  end

  test "summary for an empty list" do
    assert_equal "Nada na lista", list_summary_text(0, 0)
  end

  test "categories have decorative vector icons including unknown and missing categories" do
    (Category.all.to_a + [ nil, Category.new(name: "Desconhecida") ]).each do |category|
      fragment = Nokogiri::HTML.fragment(category_icon(category))
      assert_equal "true", fragment.at_css(".category-icon")["aria-hidden"]
      assert_equal "false", fragment.at_css("svg")["focusable"]
      assert fragment.at_css("svg path")["d"].present?
      assert_empty fragment.text.strip
    end
    assert_includes category_icon(categories(:produce)), 'data-tone="produce"'
    assert_includes category_icon(nil), 'data-tone="other"'
  end

  def result(outcome, name = "Item", category: nil)
    Household::AddResult.new(ListItem.new(name: name, category: category), outcome)
  end

  test "single add notice names the item and where it went" do
    assert_equal "Leite adicionado em Laticínios", add_item_notice(result(:created, "Leite", category: categories(:dairy)))
    assert_equal "Coisa adicionado em Sem categoria", add_item_notice(result(:created, "Coisa"))
    assert_equal "Leite voltou para a lista", add_item_notice(result(:revived, "Leite"))
    assert_equal "Leite já está na lista", add_item_notice(result(:duplicate, "Leite"))
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
