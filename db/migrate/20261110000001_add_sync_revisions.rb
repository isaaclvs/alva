# Shared-change revisions and deletion tombstones (see
# docs/plans/android-sync-contract-v1.md). A client follows a household by its
# revision, never by updated_at or a device clock.
class AddSyncRevisions < ActiveRecord::Migration[8.1]
  def change
    add_column :households, :sync_revision, :integer, null: false, default: 0

    # Revisions that no household owns; the catalog dictionary lives here.
    create_table :sync_scopes do |t|
      t.string :name, null: false
      t.integer :revision, null: false, default: 0

      t.timestamps
    end
    add_index :sync_scopes, :name, unique: true

    create_table :sync_tombstones do |t|
      t.string :record_type, null: false
      t.string :public_id, null: false
      t.string :scope, null: false
      t.references :household, null: true, foreign_key: true
      t.integer :revision, null: false

      t.timestamps
    end
    add_index :sync_tombstones, :public_id, unique: true
    add_index :sync_tombstones, [ :household_id, :revision ]
  end
end
