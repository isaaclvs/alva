require "test_helper"

class CategoryTest < ActiveSupport::TestCase
  test "valid with name and position" do
    assert categories(:produce).valid?
  end

  test "requires name" do
    category = Category.new(name: "", position: 1)
    assert_not category.valid?
    assert_includes category.errors[:name], "can't be blank"
  end

  test "requires position" do
    category = Category.new(name: "Bebidas", position: nil)
    assert_not category.valid?
    assert_includes category.errors[:position], "can't be blank"
  end

  test "name must be unique" do
    category = Category.new(name: categories(:produce).name, position: 5)
    assert_not category.valid?
    assert_includes category.errors[:name], "has already been taken"
  end
end
