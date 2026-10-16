# TextNormalizer now reduces plurals ("Ovos" → "ovo"), so keys stored with the
# old rules would no longer match what users type.
class RenormalizeCatalogItemNames < ActiveRecord::Migration[8.1]
  class CatalogItem < ActiveRecord::Base; end

  def up
    CatalogItem.find_each do |item|
      item.update_columns(normalized_name: TextNormalizer.call(item.name))
    end
  end

  def down
    raise ActiveRecord::IrreversibleMigration
  end
end
