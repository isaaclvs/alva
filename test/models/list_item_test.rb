require "test_helper"

class ListItemTest < ActiveSupport::TestCase
  test "valid with name, household and category" do
    assert list_items(:milk).valid?
  end

  test "valid without category" do
    item = ListItem.new(name: "Coisa nova", household: households(:home))
    assert item.valid?
    assert_nil item.category
  end

  test "requires name" do
    item = ListItem.new(name: "", household: households(:home))
    assert_not item.valid?
    assert_includes item.errors[:name], "can't be blank"
  end

  test "requires household" do
    item = ListItem.new(name: "Arroz")
    assert_not item.valid?
    assert_includes item.errors[:household], "must exist"
  end

  test "defaults to pending" do
    item = ListItem.create!(name: "Arroz", household: households(:home))
    assert item.pending?
  end

  test "can be marked as purchased" do
    item = list_items(:milk)
    item.purchased!
    assert item.reload.purchased?
  end

  test "household has many list items" do
    assert_equal list_items(:milk, :detergent).sort, households(:home).list_items.sort
  end

  test "category has many list items" do
    assert_equal [ list_items(:milk) ], categories(:dairy).list_items.to_a
  end

  test "auto-categorizes on create from dictionary" do
    item = ListItem.create!(name: "Leite", household: households(:home))
    assert_equal categories(:dairy), item.category
  end

  test "auto-categorizes ignoring case and accents" do
    item = ListItem.create!(name: "MACA", household: households(:home))
    assert_equal categories(:produce), item.category
  end

  test "falls back to Outros for unknown names" do
    item = ListItem.create!(name: "Coisa desconhecida", household: households(:home))
    assert_equal categories(:other), item.category
  end

  test "keeps explicitly assigned category" do
    item = ListItem.create!(name: "Leite", household: households(:home), category: categories(:other))
    assert_equal categories(:other), item.category
  end

  test "toggle_purchased! flips status and purchased_at" do
    item = list_items(:milk)
    item.toggle_purchased!
    assert item.purchased?
    assert_not_nil item.purchased_at

    item.toggle_purchased!
    assert item.pending?
    assert_nil item.purchased_at
  end
end
