class AddNormalizedNameAndPendingItemIndex < ActiveRecord::Migration[8.1]
  class ListItem < ActiveRecord::Base
  end

  def up
    add_column :list_items, :normalized_name, :string
    add_column :list_items, :lock_version, :integer, null: false, default: 0

    ListItem.reset_column_information
    ListItem.find_each do |item|
      item.update_columns(normalized_name: TextNormalizer.call(item.name))
    end

    change_column_null :list_items, :normalized_name, false
    duplicates = select_values(<<~SQL)
      SELECT household_id || ':' || normalized_name || ' (' || COUNT(*) || ')'
      FROM list_items
      WHERE status = 0 AND shopping_trip_id IS NULL
      GROUP BY household_id, normalized_name
      HAVING COUNT(*) > 1
    SQL
    if duplicates.any?
      raise ActiveRecord::MigrationError,
        "Cannot add pending-item uniqueness index; resolve duplicate normalized names first: #{duplicates.join(', ')}"
    end

    add_index :list_items, [ :household_id, :normalized_name ], unique: true,
      where: "status = 0 AND shopping_trip_id IS NULL",
      name: "index_pending_list_items_on_household_and_normalized_name"
  end

  def down
    remove_index :list_items, name: "index_pending_list_items_on_household_and_normalized_name"
    remove_column :list_items, :normalized_name
    remove_column :list_items, :lock_version
  end
end
