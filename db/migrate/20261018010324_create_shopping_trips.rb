class CreateShoppingTrips < ActiveRecord::Migration[8.1]
  def change
    create_table :shopping_trips do |t|
      t.references :household, null: false, foreign_key: true
      t.datetime :finished_at, null: false

      t.timestamps
    end
  end
end
