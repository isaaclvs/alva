class CreateSyncCommands < ActiveRecord::Migration[8.1]
  # A re-authorized phone gets a new AndroidDevice row (new id, new secret).
  # `lineage_id` is the identity that survives it, so operations the phone sent
  # before can still be resolved afterwards. Every existing credential starts
  # its own lineage; nothing is merged retroactively.
  class MigrationDevice < ActiveRecord::Base
    self.table_name = "android_devices"
  end

  def up
    add_column :android_devices, :lineage_id, :string
    MigrationDevice.reset_column_information
    MigrationDevice.find_each { |device| device.update_columns(lineage_id: SecureRandom.uuid) }
    change_column_null :android_devices, :lineage_id, false
    add_index :android_devices, :lineage_id

    # The idempotency ledger. A row exists only for a command whose effect was
    # committed, and is written in the same transaction as that effect.
    create_table :sync_commands do |t|
      t.references :household, null: false, foreign_key: true, index: false
      t.string :command_id, null: false
      t.references :android_device, null: false, foreign_key: true
      t.string :lineage_id, null: false
      t.string :command_type, null: false
      t.string :depends_on
      t.string :content_digest, null: false
      t.string :status, null: false
      t.integer :revision, null: false
      t.json :data, null: false
      t.timestamps
    end

    add_index :sync_commands, [ :household_id, :command_id ], unique: true
    add_check_constraint :sync_commands, "status IN ('applied', 'noop')", name: "sync_commands_status_allowed"
  end

  def down
    drop_table :sync_commands
    remove_index :android_devices, :lineage_id
    remove_column :android_devices, :lineage_id
  end
end
