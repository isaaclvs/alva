class AddAccessDigestToAndroidDevices < ActiveRecord::Migration[8.1]
  def change
    add_column :android_devices, :access_digest, :string, null: false, default: ""
    change_column_default :android_devices, :access_digest, from: "", to: nil
  end
end
