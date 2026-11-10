# Stable, client-generatable identity for every record the sync protocol
# exposes. The integer primary key stays internal; public_id is the UUID the
# Android app stores and sends back, and it can be generated offline before the
# record exists on the server.
class AddPublicIds < ActiveRecord::Migration[8.1]
  TABLES = %i[households categories catalog_items list_items habitual_items shopping_trips].freeze

  def up
    TABLES.each do |table|
      add_column table, :public_id, :string
      backfill(table)
      change_column_null table, :public_id, false
      add_index table, :public_id, unique: true
    end
  end

  def down
    TABLES.each { |table| remove_column table, :public_id }
  end

  private

  # Reads each table directly instead of using the app models: a migration must
  # not depend on today's model code.
  def backfill(table)
    klass = Class.new(ActiveRecord::Base) { self.table_name = table.to_s }
    klass.reset_column_information
    klass.find_each { |row| row.update_columns(public_id: SecureRandom.uuid) }
  end
end
