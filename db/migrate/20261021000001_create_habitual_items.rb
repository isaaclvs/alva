class CreateHabitualItems < ActiveRecord::Migration[8.1]
  def change
    create_table :habitual_items do |t|
      t.references :household, null: false, foreign_key: true
      t.string :name, null: false
      t.string :normalized_name, null: false

      t.timestamps
    end

    add_index :habitual_items, [ :household_id, :normalized_name ], unique: true,
      name: "index_habitual_items_on_household_and_normalized_name"
  end
end
