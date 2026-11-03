class CreateAndroidDevices < ActiveRecord::Migration[8.1]
  def change
    create_table :android_devices do |t|
      t.references :household, null: false, foreign_key: true
      t.string :name, null: false
      t.string :token_digest, null: false
      t.datetime :revoked_at
      t.timestamps
    end

    add_index :android_devices, :token_digest, unique: true
  end
end
