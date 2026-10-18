# Dictionary entries learned from a category picked by hand in the app.
# db:seed (run on every deploy) must not overwrite them with catalog.yml.
class AddLearnedToCatalogItems < ActiveRecord::Migration[8.1]
  def change
    add_column :catalog_items, :learned, :boolean, null: false, default: false
  end
end
