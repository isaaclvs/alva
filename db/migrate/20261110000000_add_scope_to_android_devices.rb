class AddScopeToAndroidDevices < ActiveRecord::Migration[8.1]
  def change
    # Every existing credential is the widget's add_only token; the default and
    # the check constraint keep that the only way to create or promote a device
    # without an explicit sync enrollment.
    add_column :android_devices, :scope, :string, null: false, default: "add_only"
    add_check_constraint :android_devices, "scope IN ('add_only', 'sync')", name: "android_devices_scope_allowed"
  end
end
