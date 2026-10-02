class CreateListItems < ActiveRecord::Migration[8.1]
  def change
    create_table :list_items do |t|
      t.string :name, null: false
      t.references :category, null: true, foreign_key: true
      t.integer :status, null: false, default: 0
      t.integer :position
      t.datetime :purchased_at
      t.references :household, null: false, foreign_key: true

      t.timestamps
    end
  end
end
