class AddShoppingTripToListItems < ActiveRecord::Migration[8.1]
  def change
    add_reference :list_items, :shopping_trip, null: true, foreign_key: true
  end
end
