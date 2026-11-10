class CreateSyncAliases < ActiveRecord::Migration[8.1]
  # When two phones add the same name, the server keeps one canonical item and
  # the UUID the other phone generated becomes an alias of it, so later commands
  # that cite that UUID reach the right item.
  #
  # `public_id` is unique across all households, like the item UUIDs and the
  # tombstones it shares a namespace with: a UUID can be claimed once. Lookups
  # always filter by household. `canonical_public_id` is a plain string, not a
  # foreign key: the canonical item may later be archived or removed, and the
  # alias must keep answering "gone" instead of disappearing.
  def change
    create_table :sync_aliases do |t|
      t.references :household, null: false, foreign_key: true, index: false
      t.string :public_id, null: false
      t.string :canonical_public_id, null: false
      t.string :command_id, null: false
      t.timestamps
    end

    add_index :sync_aliases, :public_id, unique: true
    add_index :sync_aliases, [ :household_id, :canonical_public_id ]
  end
end
